package org.alephium.flow.client

import akka.actor.{ActorRef, Props}
import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.storage.{AddBlockResult, BlockChainHandler, BlockFlow}
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

class FairMiner(address: ED25519PublicKey, node: Node)(implicit val config: PlatformConfig)
    extends BaseActor
    with FairMinerState {
  val blockFlow: BlockFlow  = node.blockFlow
  val actualMiner: ActorRef = context.actorOf(ActualMiner.props)

  def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      log.info("Start mining")
      initializeState()
      startNewTask()
      context become (handleMining orElse awaitStop)
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      log.info("Stop mining")
      context become awaitStart
  }

  def handleMining: Receive = {
    case FairMiner.MiningResult(blockOpt, chainIndex, miningCount) =>
      val to = chainIndex.to.value
      increaseCounts(to, miningCount)
      blockOpt match {
        case Some(block) =>
          val miningCount = getMiningCount(to)
          removeTask(to)
          log.debug(
            s"""MiningCounts: ${(0 until config.groups).map(getMiningCount).mkString(",")}""")
          log.info(
            s"A new block ${block.shortHex} is mined for $chainIndex, miningCount: $miningCount, target: ${block.header.target}")
          val blockHandler = node.allHandlers.getBlockHandler(chainIndex)
          blockHandler ! BlockChainHandler.AddBlocks(AVector(block), Local)
        case None => tryToRefresh(to)
      }
      startNewTask()
    case AddBlockResult.Success =>
      node.allHandlers.blockHandlers.foreach {
        case (chainIndex, handler) =>
          if (handler == sender()) {
            assert(chainIndex.from == config.mainGroup)
            refresh(chainIndex.to.value)
          }
      }
    case e: AddBlockResult =>
      log.error(s"Error in adding new block: ${e.toString}")
      context stop self
  }

  def startNewTask(): Unit = {
    val (to, template) = pickNextTemplate()
    val chainIndex     = ChainIndex(config.mainGroup.value, to)
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
