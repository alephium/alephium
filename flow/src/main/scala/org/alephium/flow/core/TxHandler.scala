package org.alephium.flow.core

import akka.actor.Props

import org.alephium.flow.core.mempool.MemPool
import org.alephium.flow.core.validation.{InvalidTxStatus, TxValidation, ValidTx}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.message.{Message, SendTxs}
import org.alephium.protocol.model.{ChainIndex, Transaction}
import org.alephium.util.{ActorRefT, AVector, BaseActor}

object TxHandler {
  def props(blockFlow: BlockFlow, cliqueManager: ActorRefT[CliqueManager.Command])(
      implicit config: PlatformConfig): Props =
    Props(new TxHandler(blockFlow, cliqueManager))

  sealed trait Command
  final case class AddTx(tx: Transaction, origin: DataOrigin) extends Command

  sealed trait Event
  final case object AddSucceeded extends Event
  final case object AddFailed    extends Event
}

class TxHandler(blockFlow: BlockFlow, cliqueManager: ActorRefT[CliqueManager.Command])(
    implicit config: PlatformConfig)
    extends BaseActor {
  override def receive: Receive = {
    case TxHandler.AddTx(tx, origin) => handleTx(tx, origin)
  }

  def handleTx(tx: Transaction, origin: DataOrigin): Unit = {
    val fromGroup  = tx.fromGroup
    val toGroup    = tx.toGroup
    val chainIndex = ChainIndex(fromGroup, toGroup)
    val mempool    = blockFlow.getPool(chainIndex)
    if (!mempool.contains(chainIndex, tx)) {
      TxValidation.validateNonCoinbase(tx, blockFlow) match {
        case Right(s: InvalidTxStatus) =>
          log.warning(s"failed in validating tx ${tx.shortHex} due to $s")
          addFailed()
        case Right(_: ValidTx.type) =>
          handleValidTx(chainIndex, tx, mempool, origin)
        case Right(unexpected) =>
          log.warning(s"Unexpected pattern matching $unexpected")
          addFailed()
        case Left(e) =>
          log.warning(s"IO failed in validating tx ${tx.shortHex} due to $e")
          addFailed()
      }
    } else {
      log.debug(s"tx ${tx.shortHex} is already included")
      addFailed()
    }
  }

  def handleValidTx(chainIndex: ChainIndex,
                    tx: Transaction,
                    mempool: MemPool,
                    origin: DataOrigin): Unit = {
    val count = mempool.add(chainIndex, AVector((tx, 1.0)))
    log.info(s"Add tx ${tx.shortHex} for $chainIndex, #$count txs added")
    val txMessage = Message.serialize(SendTxs(AVector(tx)))
    cliqueManager ! CliqueManager.BroadCastTx(tx, txMessage, chainIndex, origin)
    addSucceeded()
  }

  def addSucceeded(): Unit = {
    sender() ! TxHandler.AddSucceeded
  }

  def addFailed(): Unit = {
    sender() ! TxHandler.AddFailed
  }
}
