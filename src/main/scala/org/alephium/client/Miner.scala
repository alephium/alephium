package org.alephium.client

import java.math.BigInteger
import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.protocol.message.SendBlocks
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.storage.BlockPool
import org.alephium.util.BaseActor

object Miner {
  def props(address: ED25519PublicKey, blockPool: ActorRef): Props =
    Props(new Miner(address, blockPool))

  sealed trait Command
  case object Start                                                extends Command
  case object Stop                                                 extends Command
  case class Peer(remote: InetSocketAddress, tcpHandler: ActorRef) extends Command
  case class Pool(transaction: Transaction)                        extends Command
  case class Nonce(nonce: BigInteger)                              extends Command

  def tryMine(deps: Seq[Keccak256],
              transactions: Seq[Transaction],
              nonce: BigInteger): Option[Block] = {
    val block = Block.from(deps, transactions, nonce)
    if (isDifficult(Keccak256.hash(block.hash))) {
      Some(block)
    } else None
  }

  def isDifficult(hash: Keccak256): Boolean = {
    hash.bytes(0) == 0 //TODO: improve this
  }
}

case class Miner(address: ED25519PublicKey, blockPool: ActorRef) extends BaseActor {
  private val peers           = collection.mutable.HashMap.empty[InetSocketAddress, ActorRef]
  private val transactionPool = collection.mutable.HashMap.empty[Keccak256, Transaction]

  override def receive: Receive = idle

  def common: Receive = {
    case Miner.Peer(remote, tcpHandler) =>
      peers += remote -> tcpHandler
    case Miner.Pool(transaction) =>
      transactionPool += transaction.hash -> transaction
  }

  def idle: Receive = common orElse {
    case Miner.Start =>
      context become collect
  }

  def mine(deps: Seq[Keccak256], transactions: Seq[Transaction]): Receive = common orElse {
    case Miner.Stop => context become idle
    case Miner.Nonce(nonce) =>
      Miner.tryMine(deps, transactions, nonce) match {
        case Some(block) =>
          broadcast(block)
          blockPool ! BlockPool.AddBlocks(Seq(block))
          blockPool ! BlockPool.GetBestHeader
          context become collect
        case None =>
          self ! Miner.Nonce(nonce add BigInteger.ONE)
      }
  }

  def collect: Receive = common orElse {
    case BlockPool.BestHeader(header) =>
      val transaction = Transaction.coinbase(address, BigInteger.ONE)
      context become mine(Seq(header.hash), Seq(transaction))
      self ! Miner.Nonce(BigInteger.ONE)
  }

  private def broadcast(block: Block): Unit = {
    peers.values.foreach(_ ! SendBlocks(Seq(block)))
  }
}
