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
import org.alephium.flow.setting.NetworkSetting
import org.alephium.flow.validation.{InvalidTxStatus, NonCoinbaseValidation}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.{Message, SendTxs}
import org.alephium.protocol.model.{ChainIndex, TransactionTemplate}
import org.alephium.util.{AVector, BaseActor, EventStream}

object TxHandler {
  def props(blockFlow: BlockFlow)(implicit groupConfig: GroupConfig,
                                  networkSetting: NetworkSetting): Props =
    Props(new TxHandler(blockFlow))

  sealed trait Command
  final case class AddTx(tx: TransactionTemplate, origin: DataOrigin) extends Command

  sealed trait Event
  final case class AddSucceeded(txId: Hash) extends Event
  final case class AddFailed(txId: Hash)    extends Event
}

class TxHandler(blockFlow: BlockFlow)(implicit groupConfig: GroupConfig,
                                      networkSetting: NetworkSetting)
    extends BaseActor
    with EventStream.Publisher {
  private val nonCoinbaseValidation = NonCoinbaseValidation.build

  override def receive: Receive = {
    case TxHandler.AddTx(tx, origin) => handleTx(tx, origin)
  }

  def handleTx(tx: TransactionTemplate, origin: DataOrigin): Unit = {
    val fromGroup  = tx.fromGroup
    val toGroup    = tx.toGroup
    val chainIndex = ChainIndex(fromGroup, toGroup)
    val mempool    = blockFlow.getPool(chainIndex)
    if (!mempool.contains(chainIndex, tx)) {
      nonCoinbaseValidation.validateMempoolTxTemplate(tx, blockFlow) match {
        case Left(Right(s: InvalidTxStatus)) =>
          log.warning(s"failed in validating tx ${tx.id.shortHex} due to $s")
          addFailed(tx)
        case Right(_) =>
          handleValidTx(chainIndex, tx, mempool, origin)
        case Left(Left(e)) =>
          log.warning(s"IO failed in validating tx ${tx.id.shortHex} due to $e")
          addFailed(tx)
      }
    } else {
      log.debug(s"tx ${tx.id.shortHex} is already included")
      addFailed(tx)
    }
  }

  def handleValidTx(chainIndex: ChainIndex,
                    tx: TransactionTemplate,
                    mempool: MemPool,
                    origin: DataOrigin): Unit = {
    val count = mempool.add(chainIndex, AVector(tx))
    log.info(s"Add tx ${tx.id.shortHex} for $chainIndex, #$count txs added")
    val txMessage = Message.serialize(SendTxs(AVector(tx)), networkSetting.networkType)
    val event     = CliqueManager.BroadCastTx(tx, txMessage, chainIndex, origin)
    publishEvent(event)
    addSucceeded(tx)
  }

  def addSucceeded(tx: TransactionTemplate): Unit = {
    sender() ! TxHandler.AddSucceeded(tx.id)
  }

  def addFailed(tx: TransactionTemplate): Unit = {
    sender() ! TxHandler.AddFailed(tx.id)
  }
}
