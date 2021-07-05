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
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.setting.{MemPoolSetting, NetworkSetting}
import org.alephium.flow.validation.{InvalidTxStatus, TxValidation, TxValidationResult}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.{Message, SendTxs}
import org.alephium.protocol.model.{ChainIndex, TransactionTemplate}
import org.alephium.serde.serialize
import org.alephium.util.{AVector, BaseActor, EventStream, Hex, TimeStamp}

object TxHandler {
  def props(
      blockFlow: BlockFlow
  )(implicit
      groupConfig: GroupConfig,
      networkSetting: NetworkSetting,
      memPoolSetting: MemPoolSetting
  ): Props =
    Props(new TxHandler(blockFlow))

  sealed trait Command
  final case class AddToSharedPool(txs: AVector[TransactionTemplate], origin: DataOrigin)
      extends Command
  final case class Broadcast(txs: AVector[TransactionTemplate]) extends Command
  final case class AddToGrandPool(txs: AVector[TransactionTemplate], origin: DataOrigin)
      extends Command
  case object CleanMempool extends Command

  sealed trait Event
  final case class AddSucceeded(txId: Hash) extends Event
  final case class AddFailed(txId: Hash)    extends Event
}

class TxHandler(blockFlow: BlockFlow)(implicit
    groupConfig: GroupConfig,
    networkSetting: NetworkSetting,
    memPoolSetting: MemPoolSetting
) extends BaseActor
    with EventStream.Publisher {
  private val nonCoinbaseValidation = TxValidation.build

  override def preStart(): Unit = {
    super.preStart()
    schedule(self, TxHandler.CleanMempool, memPoolSetting.cleanFrequency)
  }

  override def receive: Receive = {
    case TxHandler.AddToSharedPool(txs, origin) =>
      txs.foreach(handleTx(_, origin, nonCoinbaseValidation.validateMempoolTxTemplate))
    case TxHandler.AddToGrandPool(txs, origin) =>
      txs.foreach(handleTx(_, origin, nonCoinbaseValidation.validateGrandPoolTxTemplate))
    case TxHandler.Broadcast(txs) =>
      txs.groupBy(_.chainIndex).foreach { case (chainIndex, txs) =>
        broadCast(chainIndex, txs, DataOrigin.Local)
      }
    case TxHandler.CleanMempool =>
      log.debug(s"Start to clean tx pools")
      blockFlow.grandPool.clean(
        blockFlow,
        TimeStamp.now().minusUnsafe(memPoolSetting.cleanFrequency)
      )
  }

  private def hex(tx: TransactionTemplate): String = {
    Hex.toHexString(serialize(tx))
  }

  def handleTx(
      tx: TransactionTemplate,
      origin: DataOrigin,
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
          handleValidTx(chainIndex, tx, mempool, origin, acknowledge = true)
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
      origin: DataOrigin,
      acknowledge: Boolean
  ): Unit = {
    val result = mempool.addNewTx(chainIndex, tx)
    log.info(s"Add tx ${tx.id.shortHex} for $chainIndex, type: $result")
    result match {
      case MemPool.AddedToSharedPool =>
        // We don't broadcast txs that are pending locally
        broadCast(chainIndex, AVector(tx), origin)
      case _ => ()
    }
    if (acknowledge) {
      addSucceeded(tx)
    }
  }

  def broadCast(
      chainIndex: ChainIndex,
      txs: AVector[TransactionTemplate],
      origin: DataOrigin
  ): Unit = {
    val txMessage = Message.serialize(SendTxs(txs), networkSetting.networkType)
    val event     = CliqueManager.BroadCastTx(txs, txMessage, chainIndex, origin)
    publishEvent(event)
  }

  def addSucceeded(tx: TransactionTemplate): Unit = {
    sender() ! TxHandler.AddSucceeded(tx.id)
  }

  def addFailed(tx: TransactionTemplate): Unit = {
    sender() ! TxHandler.AddFailed(tx.id)
  }
}
