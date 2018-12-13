package org.alephium.client

import akka.actor.Props
import org.alephium.constant.Network
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.storage.{AddBlockResult, ChainHandler, FlowHandler}
import org.alephium.storage.ChainHandler.BlockOrigin.Local
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
      if (chainIndex.accept(block.hash)) block else iter(nonce + 1)
    }

    iter(0)
  }
}

class Miner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex) extends BaseActor {
  import node.blockHandlers

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      log.info("Start mining")
      blockHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      context become awaitStart
  }

  protected def _mine(deps: Seq[Keccak256], transactions: Seq[Transaction], lastTs: Long): Receive = {
    case Miner.Nonce(from, to) =>
      tryMine(deps, transactions, from, to) match {
        case Some(block) =>
          log.info(s"A new block is mined since $lastTs")
          val chainIndex = ChainIndex.fromHash(block.hash)
          blockHandlers.getHandler(chainIndex) ! ChainHandler.AddBlocks(Seq(block), Local)
        case None =>
          self ! Miner.Nonce(to, 2 * to - from)
      }
    case _: AddBlockResult =>
      blockHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect
  }

  def mine(deps: Seq[Keccak256], transactions: Seq[Transaction], lastTs: Long): Receive =
    _mine(deps, transactions, lastTs) orElse awaitStop

  protected def _collect: Receive = {
    case FlowHandler.BlockFlowTemplate(deps) =>
      assert(deps.size == (2 * Network.groups - 1))
      val transaction = Transaction.coinbase(address, 1)
      val chainDep    = deps.view.takeRight(Network.groups)(chainIndex.to)
      val lastTs      = node.blockFlow.getBlock(chainDep).blockHeader.timestamp
      context become mine(deps, Seq(transaction), lastTs)
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
