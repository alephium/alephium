package org.alephium.flow.client

import akka.actor.{ActorRef, Props}
import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.storage.BlockChainHandler
import org.alephium.flow.storage.FlowHandler.BlockFlowTemplate
import org.alephium.protocol.model.{Block, ChainIndex, Transaction}
import org.alephium.util.{AVector, BaseActor}

import scala.annotation.tailrec
import scala.util.Random

object FairMiner {
  def props(address: ED25519PublicKey, node: Node)(implicit config: PlatformConfig): Props =
    Props(new FairMiner(address, node))

  sealed trait Command
  case class MiningResult(blockOpt: Option[Block], chainIndex: ChainIndex, miningCount: BigInt)
      extends Command
}

class FairMiner(address: ED25519PublicKey, node: Node)(implicit config: PlatformConfig)
    extends BaseActor {
  val miningCounts = Array.fill[BigInt](config.groups)(0)
  val pendingTasks = Array.tabulate[BlockFlowTemplate](config.groups) { to =>
    val index = ChainIndex(config.mainGroup.value, to)
    node.blockFlow.prepareBlockFlowUnsafe(index)
  }
  val actualMiner: ActorRef = context.actorOf(ActualMiner.props)

  def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      log.info("Start mining")
      startOneTask()
      context become (awaitMining orElse awaitStop)
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      log.info("Stop mining")
      context become awaitStart
  }

  def awaitMining: Receive = {
    case FairMiner.MiningResult(blockOpt, chainIndex, miningCount) =>
      val to = chainIndex.to.value
      miningCounts(to) = miningCounts(to) + miningCount
      blockOpt.foreach { block =>
        val blockHandler = node.allHandlers.getBlockHandler(chainIndex)
        val miningCount  = miningCounts(to)
        log.info(
          s"A new block ${block.shortHex} is mined for $chainIndex, miningCount: $miningCount, target: ${block.header.target}")
        blockHandler ! BlockChainHandler.AddBlocks(AVector(block), Local)
      }
      startOneTask()
  }

  def prepareTask(to: Int): Unit = {
    val index    = ChainIndex(config.mainGroup.value, to)
    val template = pendingTasks(to)
    mine(template, index)
  }

  def startOneTask(): Unit = {
    val toTry    = miningCounts.zipWithIndex.minBy(_._1)._2
    val index    = ChainIndex(config.mainGroup.value, toTry)
    val template = pendingTasks(toTry)
    mine(template, index)
  }

  def mine(template: BlockFlowTemplate, chainIndex: ChainIndex): Unit = {
    // scalastyle:off magic.number
    val transactions = AVector.tabulate(1000)(Transaction.coinbase(address, _))
    // scalastyle:on magic.number
    val blockTemplate = BlockTemplate(template.deps, template.target, transactions)
    actualMiner ! ActualMiner.Task(blockTemplate, chainIndex)
  }
}

object ActualMiner {
  def props(implicit config: PlatformConfig): Props =
    Props(new ActualMiner)

  sealed trait Command
  case class Task(template: BlockTemplate, index: ChainIndex) extends Command
}

class ActualMiner(implicit config: PlatformConfig) extends BaseActor {
  import ActualMiner._

  override def receive: Receive = {
    case Task(template, index) =>
      mine(template, index) match {
        case Some((block, miningCount)) =>
          sender() ! FairMiner.MiningResult(Some(block), index, miningCount)
        case None =>
          sender() ! FairMiner.MiningResult(None, index, config.nonceStep)
      }
  }

  def mine(template: BlockTemplate, chainIndex: ChainIndex): Option[(Block, BigInt)] = {
    val nonceStart = BigInt(Random.nextInt(Integer.MAX_VALUE))
    val nonceEnd   = nonceStart + config.nonceStep

    @tailrec
    def iter(current: BigInt): Option[(Block, BigInt)] = {
      if (current < nonceEnd) {
        val header = template.buildHeader(current)
        if (chainIndex.validateDiff(header))
          Some((Block(header, template.transactions), current - nonceStart + 1))
        else iter(current + 1)
      } else None
    }
    iter(nonceStart)
  }
}
