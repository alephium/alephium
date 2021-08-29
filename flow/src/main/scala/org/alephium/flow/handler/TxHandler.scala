// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.handler

import akka.actor.Props

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.network.sync.FetchState
import org.alephium.flow.setting.{MemPoolSetting, NetworkSetting}
import org.alephium.flow.validation.{InvalidTxStatus, TxValidation, TxValidationResult}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{ChainIndex, TransactionTemplate}
import org.alephium.serde.serialize
import org.alephium.util.{AVector, BaseActor, Cache, EventStream, Hex, TimeStamp}

object TxHandler {
  def props(blockFlow: BlockFlow)(implicit
      brokerConfig: BrokerConfig,
      memPoolSetting: MemPoolSetting,
      networkSetting: NetworkSetting
  ): Props =
    Props(new TxHandler(blockFlow))

  sealed trait Command
  final case class AddToSharedPool(txs: AVector[TransactionTemplate])            extends Command
  final case class Broadcast(txs: AVector[TransactionTemplate])                  extends Command
  final case class AddToGrandPool(txs: AVector[TransactionTemplate])             extends Command
  final case class TxAnnouncements(hashes: AVector[(ChainIndex, AVector[Hash])]) extends Command
  case object CleanMempool                                                       extends Command
  private case object BroadcastTxs                                               extends Command

  sealed trait Event
  final case class AddSucceeded(txId: Hash) extends Event
  final case class AddFailed(txId: Hash)    extends Event

  val MaxDownloadTimes: Int = 2
}

class TxHandler(blockFlow: BlockFlow)(implicit
    brokerConfig: BrokerConfig,
    memPoolSetting: MemPoolSetting,
    networkSetting: NetworkSetting
) extends BaseActor
    with EventStream.Publisher {
  private val nonCoinbaseValidation = TxValidation.build
  val maxCapacity: Int              = (brokerConfig.groupNumPerBroker * brokerConfig.groups * 10) * 32
  val fetching: FetchState[Hash] =
    FetchState[Hash](maxCapacity, networkSetting.syncExpiryPeriod, TxHandler.MaxDownloadTimes)
  val txsBuffer: Cache[TransactionTemplate, Unit] =
    Cache.fifo[TransactionTemplate, Unit](maxCapacity)

  override def preStart(): Unit = {
    super.preStart()
    schedule(self, TxHandler.CleanMempool, memPoolSetting.cleanFrequency)
    scheduleOnce(self, TxHandler.BroadcastTxs, memPoolSetting.batchBroadcastTxsFrequency)
  }

  override def receive: Receive = {
    case TxHandler.AddToSharedPool(txs) =>
      txs.foreach(handleTx(_, nonCoinbaseValidation.validateMempoolTxTemplate))
    case TxHandler.AddToGrandPool(txs) =>
      txs.foreach(handleTx(_, nonCoinbaseValidation.validateGrandPoolTxTemplate))
    case TxHandler.Broadcast(txs)          => txs.foreach(tx => txsBuffer.put(tx, ()))
    case TxHandler.TxAnnouncements(hashes) => handleAnnouncements(hashes)
    case TxHandler.BroadcastTxs            => broadcastTxs()
    case TxHandler.CleanMempool =>
      log.debug("Start to clean tx pools")
      blockFlow.grandPool.clean(
        blockFlow,
        TimeStamp.now().minusUnsafe(memPoolSetting.cleanFrequency)
      )
  }

  private def handleAnnouncements(hashes: AVector[(ChainIndex, AVector[Hash])]): Unit = {
    val timestamp = TimeStamp.now()
    val invs = hashes.fold(AVector.empty[(ChainIndex, AVector[Hash])]) {
      case (acc, (chainIndex, txHashes)) =>
        val mempool = blockFlow.getMemPool(chainIndex)
        val selected = txHashes.filter { hash =>
          !mempool.contains(chainIndex, hash) &&
          fetching.needToFetch(hash, timestamp)
        }
        if (selected.isEmpty) {
          acc
        } else {
          acc :+ ((chainIndex, selected))
        }
    }
    if (invs.nonEmpty) {
      sender() ! BrokerHandler.DownloadTxs(invs)
    }
  }

  private def hex(tx: TransactionTemplate): String = {
    Hex.toHexString(serialize(tx))
  }

  def handleTx(
      tx: TransactionTemplate,
      validate: (TransactionTemplate, BlockFlow) => TxValidationResult[Unit]
  ): Unit = {
    val chainIndex = tx.chainIndex
    val mempool    = blockFlow.getMemPool(chainIndex)
    if (mempool.contains(chainIndex, tx)) {
      log.debug(s"tx ${tx.id.toHexString} is already included")
      addFailed(tx)
    } else if (mempool.isDoubleSpending(chainIndex, tx)) {
      log.warning(s"tx ${tx.id.shortHex} is double spending: ${hex(tx)}")
      addFailed(tx)
    } else {
      validate(tx, blockFlow) match {
        case Left(Right(s: InvalidTxStatus)) =>
          log.warning(s"failed in validating tx ${tx.id.toHexString} due to $s: ${hex(tx)}")
          addFailed(tx)
        case Right(_) =>
          handleValidTx(chainIndex, tx, mempool, acknowledge = true)
        case Left(Left(e)) =>
          log.warning(s"IO failed in validating tx ${tx.id.toHexString} due to $e: ${hex(tx)}")
          addFailed(tx)
      }
    }
  }

  def handleValidTx(
      chainIndex: ChainIndex,
      tx: TransactionTemplate,
      mempool: MemPool,
      acknowledge: Boolean
  ): Unit = {
    val result = mempool.addNewTx(chainIndex, tx)
    log.info(s"Add tx ${tx.id.shortHex} for $chainIndex, type: $result")
    result match {
      case MemPool.AddedToSharedPool => txsBuffer.put(tx, ())
      case _                         => () // We don't broadcast txs that are pending locally
    }
    if (acknowledge) {
      addSucceeded(tx)
    }
  }

  def broadcastTxs(): Unit = {
    log.debug("Start to broadcast txs")
    if (!txsBuffer.isEmpty) {
      val hashes =
        txsBuffer.keys().foldLeft(Map.empty[ChainIndex, AVector[Hash]]) { case (acc, tx) =>
          val chainIndex = tx.chainIndex
          acc.get(chainIndex) match {
            case Some(hashes) => acc + (chainIndex -> (hashes :+ tx.id))
            case None         => acc + (chainIndex -> AVector(tx.id))
          }
        }

      publishEvent(InterCliqueManager.BroadCastTx(AVector.from(hashes)))
      txsBuffer.clear()
    }
    scheduleOnce(self, TxHandler.BroadcastTxs, memPoolSetting.batchBroadcastTxsFrequency)
  }

  def addSucceeded(tx: TransactionTemplate): Unit = {
    sender() ! TxHandler.AddSucceeded(tx.id)
  }

  def addFailed(tx: TransactionTemplate): Unit = {
    sender() ! TxHandler.AddFailed(tx.id)
  }
}
