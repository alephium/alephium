package org.alephium.client

import akka.actor.Props
import org.alephium.constant.Network
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.network.PeerManager
import org.alephium.protocol.message.{Message, SendBlocks}
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.storage.BlockHandler
import org.alephium.util.BaseActor

import scala.annotation.tailrec

object Miner {
  def props(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex): Props =
    Props(new Miner(address, node, chainIndex))

  sealed trait Command
  case object Start                          extends Command
  case object Stop                           extends Command
  case class Nonce(from: BigInt, to: BigInt) extends Command

  def mineGenesis(chainIndex: ChainIndex): Block = {
    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.genesis(Seq.empty, nonce)
      if (chainIndex.accept(block.miningHash)) block else iter(nonce + 1)
    }

    iter(0)
  }
}

class Miner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex) extends BaseActor {
  import node.{blockHandler, peerManager}

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      blockHandler ! BlockHandler.PrepareBlockFlow(chainIndex)
      context become collect
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      context become awaitStart
  }

  private def _mine(deps: Seq[Keccak256], transactions: Seq[Transaction]): Receive = {
    case Miner.Nonce(from, to) =>
      tryMine(deps, transactions, from, to) match {
        case Some(block) =>
          log.info("A new block is mined")
          blockHandler ! BlockHandler.AddBlocks(Seq(block))
          blockHandler ! BlockHandler.PrepareBlockFlow(chainIndex)
          peerManager ! PeerManager.BroadCast(Message(SendBlocks(Seq(block))))
          context become collect
        case None =>
          self ! Miner.Nonce(to, 2 * to - from)
      }
  }

  def mine(deps: Seq[Keccak256], transactions: Seq[Transaction]): Receive =
    _mine(deps, transactions) orElse awaitStop

  private def _collect: Receive = {
    case BlockHandler.BlockFlowTemplate(deps) =>
      val transaction = Transaction.coinbase(address, 1)
      context become mine(deps, Seq(transaction))
      self ! Miner.Nonce(0, Network.nonceStep)
  }

  def collect: Receive = _collect orElse awaitStop

  def tryMine(deps: Seq[Keccak256],
              transactions: Seq[Transaction],
              from: BigInt,
              to: BigInt): Option[Block] = {
    @tailrec
    def iter(current: BigInt): Option[Block] = {
      if (current < to) {
        val block = Block.from(deps, transactions, current)
        if (isDifficult(block)) Some(block)
        else iter(current + 1)
      } else None
    }

    iter(from)
  }

  def isDifficult(block: Block): Boolean = {
    val hash = block.miningHash
    hash.bytes(0) == chainIndex.from && hash.bytes(1) == chainIndex.to && hash.bytes(2) < 0 //TODO: improve this
  }
}
