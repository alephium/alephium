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

import org.alephium.flow.Utils
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
import org.alephium.util._

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
  private case object DownloadTxs                                                extends Command

  sealed trait Event
  final case class AddSucceeded(txId: Hash)              extends Event
  final case class AddFailed(txId: Hash, reason: String) extends Event

  final case class Announcement(
      brokerHandler: ActorRefT[BrokerHandler.Command],
      chainIndex: ChainIndex,
      hash: Hash
  )

  val MaxDownloadTimes: Int = 2
  // scalastyle:off magic.number
  val PersistenceDuration: Duration = Duration.ofSecondsUnsafe(30)
  // scalastyle:on magic.number
}

class TxHandler(blockFlow: BlockFlow)(implicit
    brokerConfig: BrokerConfig,
    memPoolSetting: MemPoolSetting,
    networkSetting: NetworkSetting
) extends IOBaseActor
    with EventStream.Publisher {
  private val nonCoinbaseValidation = TxValidation.build
  val maxCapacity: Int              = (brokerConfig.groupNumPerBroker * brokerConfig.groups * 10) * 32
  val fetching: FetchState[Hash] =
    FetchState[Hash](maxCapacity, networkSetting.syncExpiryPeriod, TxHandler.MaxDownloadTimes)
  val txsBuffer: Cache[TransactionTemplate, Unit] =
    Cache.fifo[TransactionTemplate, Unit](maxCapacity)
  val delayedTxs: Cache[TransactionTemplate, TimeStamp] =
    Cache.fifo[TransactionTemplate, TimeStamp](maxCapacity)
  val announcements: Cache[TxHandler.Announcement, Unit] =
    Cache.fifo[TxHandler.Announcement, Unit](maxCapacity)

  override def preStart(): Unit = {
    super.preStart()
    schedule(self, TxHandler.CleanMempool, memPoolSetting.cleanFrequency)
    scheduleOnce(self, TxHandler.BroadcastTxs, memPoolSetting.batchBroadcastTxsFrequency)
    scheduleOnce(self, TxHandler.DownloadTxs, memPoolSetting.batchDownloadTxsFrequency)
  }

  override def receive: Receive = {
    case TxHandler.AddToSharedPool(txs) =>
      txs.foreach(handleTx(_, nonCoinbaseValidation.validateMempoolTxTemplate))
    case TxHandler.AddToGrandPool(txs) =>
      txs.foreach(handleTx(_, nonCoinbaseValidation.validateGrandPoolTxTemplate))
    case TxHandler.Broadcast(txs)          => txs.foreach(tx => txsBuffer.put(tx, ()))
    case TxHandler.TxAnnouncements(hashes) => handleAnnouncements(hashes)
    case TxHandler.BroadcastTxs            => broadcastTxs()
    case TxHandler.DownloadTxs             => downloadTxs()
    case TxHandler.CleanMempool =>
      log.debug("Start to clean tx pools")
      blockFlow.grandPool.clean(
        blockFlow,
        TimeStamp.now().minusUnsafe(memPoolSetting.cleanFrequency)
      )
  }

  def downloadTxs(): Unit = {
    log.debug(s"Start to download txs")
    if (!announcements.isEmpty) {
      announcements
        .keys()
        .foldLeft(Map.empty[ActorRefT[BrokerHandler.Command], Map[ChainIndex, AVector[Hash]]]) {
          case (acc, TxHandler.Announcement(brokerHandler, chainIndex, hash)) =>
            val newAnns = acc.get(brokerHandler) match {
              case Some(anns) => updateChainIndexedHash(anns, chainIndex, hash)
              case None       => Map(chainIndex -> AVector(hash))
            }
            acc + (brokerHandler -> newAnns)
        }
        .foreach { case (brokerHandler, anns) =>
          val txHashes = AVector.from(anns)
          log.debug(s"Download tx announcements ${Utils.showChainIndexedDigest(txHashes)}")
          brokerHandler ! BrokerHandler.DownloadTxs(txHashes)
        }

      announcements.clear()
    }
    scheduleOnce(self, TxHandler.DownloadTxs, memPoolSetting.batchDownloadTxsFrequency)
  }

  private def handleAnnouncements(hashes: AVector[(ChainIndex, AVector[Hash])]): Unit = {
    val timestamp     = TimeStamp.now()
    val brokerHandler = ActorRefT[BrokerHandler.Command](sender())
    hashes.foreach { case (chainIndex, hashes) =>
      val mempool = blockFlow.getMemPool(chainIndex)
      hashes.foreach { hash =>
        if (
          fetching.needToFetch(hash, timestamp) &&
          !mempool.contains(chainIndex, hash)
        ) {
          val announcement = TxHandler.Announcement(brokerHandler, chainIndex, hash)
          announcements.put(announcement, ())
        }
      }
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
      addFailed(tx, s"tx ${tx.id.toHexString} is already included")
    } else if (mempool.isDoubleSpending(chainIndex, tx)) {
      addFailed(tx, s"tx ${tx.id.shortHex} is double spending: ${hex(tx)}")
    } else {
      validate(tx, blockFlow) match {
        case Left(Right(s: InvalidTxStatus)) =>
          addFailed(tx, s"Failed in validating tx ${tx.id.toHexString} due to $s: ${hex(tx)}")
        case Right(_) =>
          handleValidTx(chainIndex, tx, mempool, acknowledge = true)
        case Left(Left(e)) =>
          addFailed(tx, s"IO failed in validating tx ${tx.id.toHexString} due to $e: ${hex(tx)}")
      }
    }
  }

  private def needToDelay(chainIndex: ChainIndex, tx: TransactionTemplate): Boolean = {
    val maxLockTimeOpt =
      blockFlow.getBestPersistedWorldState(chainIndex.from).flatMap { persistedWS =>
        tx.unsigned.inputs.foldE(Option(TimeStamp.zero)) {
          case (Some(ts), input) =>
            persistedWS
              .getAssetOpt(input.outputRef)
              .map(_.map { output =>
                if (output.lockTime < ts) {
                  ts
                } else {
                  output.lockTime
                }
              })
          case (None, _) => Right(None)
        }
      }

    escapeIOError[Option[TimeStamp], Boolean](
      maxLockTimeOpt,
      {
        case Some(maxLockTime) =>
          maxLockTime.plusUnsafe(TxHandler.PersistenceDuration) > TimeStamp.now()
        case None => true
      }
    )(false)
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
      case MemPool.AddedToSharedPool =>
        if (needToDelay(chainIndex, tx)) {
          delayedTxs.put(tx, TimeStamp.now().plusUnsafe(networkSetting.txsBroadcastDelay))
        } else {
          txsBuffer.put(tx, ())
        }
      case _ => () // We don't broadcast txs that are pending locally
    }
    if (acknowledge) {
      addSucceeded(tx)
    }
  }

  private def updateChainIndexedHash(
      hashes: Map[ChainIndex, AVector[Hash]],
      chainIndex: ChainIndex,
      hash: Hash
  ): Map[ChainIndex, AVector[Hash]] = {
    hashes.get(chainIndex) match {
      case Some(chainIndexedHashes) => hashes + (chainIndex -> (chainIndexedHashes :+ hash))
      case None                     => hashes + (chainIndex -> AVector(hash))
    }
  }

  def broadcastTxs(): Unit = {
    log.debug("Start to broadcast txs")
    val hashes1 = if (!txsBuffer.isEmpty) {
      val hashes =
        txsBuffer.keys().foldLeft(Map.empty[ChainIndex, AVector[Hash]]) { case (acc, tx) =>
          updateChainIndexedHash(acc, tx.chainIndex, tx.id)
        }
      txsBuffer.clear()
      hashes
    } else {
      Map.empty[ChainIndex, AVector[Hash]]
    }

    val hashes2 = if (delayedTxs.isEmpty) {
      hashes1
    } else {
      val currentTs = TimeStamp.now()
      val (hashes, removed) = delayedTxs
        .entries()
        .takeWhile(_.getValue <= currentTs)
        .foldLeft((hashes1, AVector.empty[TransactionTemplate])) {
          case ((hashes, removed), entry) =>
            val tx = entry.getKey
            (updateChainIndexedHash(hashes, tx.chainIndex, tx.id), removed :+ tx)
        }
      removed.foreach(delayedTxs.remove)
      hashes
    }
    if (hashes2.nonEmpty) {
      publishEvent(InterCliqueManager.BroadCastTx(AVector.from(hashes2)))
    }
    scheduleOnce(self, TxHandler.BroadcastTxs, memPoolSetting.batchBroadcastTxsFrequency)
  }

  def addSucceeded(tx: TransactionTemplate): Unit = {
    sender() ! TxHandler.AddSucceeded(tx.id)
  }

  def addFailed(tx: TransactionTemplate, reason: String): Unit = {
    sender() ! TxHandler.AddFailed(tx.id, reason: String)
  }
}
