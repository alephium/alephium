package org.alephium.flow.client

import akka.actor.{ActorRef, Props}
import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.PlatformConfig
import org.alephium.flow.constant.Consensus
import org.alephium.flow.model.{BlockTemplate, ChainIndex}
import org.alephium.flow.storage.ChainHandler.BlockOrigin.Local
import org.alephium.flow.storage.{AddBlockResult, ChainHandler, FlowHandler}
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.util.BaseActor

import scala.annotation.tailrec

object Miner {
  sealed trait Command
  case object Start                          extends Command
  case object Stop                           extends Command
  case class Nonce(from: BigInt, to: BigInt) extends Command

  def mineGenesis(chainIndex: ChainIndex)(implicit config: PlatformConfig): Block = {
    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.genesis(Seq.empty, Consensus.maxMiningTarget, nonce)
      if (chainIndex.accept(block.hash)) block else iter(nonce + 1)
    }

    iter(0)
  }

  trait Builder {
    def createMiner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex)(
        implicit config: PlatformConfig): Props =
      Props(new Miner(address, node, chainIndex))
  }
}

class Miner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex)(
    implicit config: PlatformConfig)
    extends BaseActor {
  import node.blockHandlers

  val chainHandler: ActorRef = blockHandlers.getHandler(chainIndex)

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

  protected def _mine(template: BlockTemplate, lastTs: Long): Receive = {
    case Miner.Nonce(from, to) =>
      tryMine(template, from, to) match {
        case Some(block) =>
          val elapsed = System.currentTimeMillis() - lastTs
          log.info(s"A new block ${block.shortHash} is mined, elapsed $elapsed ms")
          chainHandler ! ChainHandler.AddBlocks(Seq(block), Local)
        case None =>
          self ! Miner.Nonce(to, 2 * to - from)
      }

    case AddBlockResult.Success =>
      blockHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect

    case _: AddBlockResult.Failure =>
      context stop self
  }

  def mine(template: BlockTemplate, lastTs: Long): Receive =
    _mine(template, lastTs) orElse awaitStop

  protected def _collect: Receive = {
    case FlowHandler.BlockFlowTemplate(deps, target) =>
      assert(deps.size == (2 * config.groups - 1))
      val transaction = Transaction.coinbase(address, 1)
      val chainDep    = deps.view.takeRight(config.groups)(chainIndex.to)
      val lastTs      = node.blockFlow.getBlock(chainDep).blockHeader.timestamp
      val template    = BlockTemplate(deps, target, Seq(transaction))
      context become mine(template, lastTs)
      self ! Miner.Nonce(0, config.nonceStep)
  }

  def collect: Receive = _collect orElse awaitStop

  def tryMine(template: BlockTemplate, from: BigInt, to: BigInt): Option[Block] = {
    @tailrec
    def iter(current: BigInt): Option[Block] = {
      if (current < to) {
        val block = Block.from(template.deps, template.transactions, template.target, current)
        if (isDifficult(block)) Some(block)
        else iter(current + 1)
      } else None
    }

    iter(from)
  }

  def isDifficult(block: Block): Boolean = {
    chainIndex.accept(block.hash)
  }
}
