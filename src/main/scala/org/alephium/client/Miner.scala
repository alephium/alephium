package org.alephium.client

import akka.actor.{ActorRef, Props}
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
//import org.alephium.network.PeerManager
//import org.alephium.protocol.message.{Message, SendBlocks}
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.storage.BlockPool
import org.alephium.util.BaseActor

object Miner {
  def props(address: ED25519PublicKey, blockPool: ActorRef, peerManager: ActorRef): Props =
    Props(new Miner(address, blockPool, peerManager))

  sealed trait Command
  case object Start               extends Command
  case object Stop                extends Command
  case class Nonce(nonce: BigInt) extends Command

  def tryMine(deps: Seq[Keccak256],
              transactions: Seq[Transaction],
              nonce: BigInt): Option[Block] = {
    val block = Block.from(deps, transactions, nonce)
    if (isDifficult(Keccak256.hash(block.hash))) {
      Some(block)
    } else None
  }

  def isDifficult(hash: Keccak256): Boolean = {
    hash.bytes.take(2).forall(_ == 0) //TODO: improve this
  }
}

case class Miner(address: ED25519PublicKey, blockPool: ActorRef, peerManager: ActorRef)
    extends BaseActor {

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      blockPool ! BlockPool.GetBestHeader
      context become collect
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      context become awaitStart
  }

  private def _mine(deps: Seq[Keccak256], transactions: Seq[Transaction]): Receive = {
    case Miner.Nonce(nonce) =>
      Miner.tryMine(deps, transactions, nonce) match {
        case Some(block) =>
          log.info("A new block is mined")
          blockPool ! BlockPool.AddBlocks(Seq(block))
          blockPool ! BlockPool.GetBestHeader
          /* TODO: enable broadcasting
           * peerManager ! PeerManager.BroadCast(Message(SendBlocks(Seq(block))))
           */
          context become collect
        case None =>
          self ! Miner.Nonce(nonce + 1)
      }
  }

  def mine(deps: Seq[Keccak256], transactions: Seq[Transaction]): Receive =
    _mine(deps, transactions) orElse awaitStop

  private def _collect: Receive = {
    case BlockPool.BestHeader(header) =>
      val transaction = Transaction.coinbase(address, 1)
      context become mine(Seq(header.hash), Seq(transaction))
      self ! Miner.Nonce(1)
  }

  def collect: Receive = _collect orElse awaitStop
}
