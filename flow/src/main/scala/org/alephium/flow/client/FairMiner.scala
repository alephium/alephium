package org.alephium.flow.client

import akka.actor.{ActorRef, Props}
import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.storage.FlowHandler.BlockFlowTemplate
import org.alephium.flow.storage.{AddBlockResult, BlockChainHandler, FlowHandler}
import org.alephium.protocol.model.{Block, ChainIndex, Transaction}
import org.alephium.util.{AVector, BaseActor}

import scala.annotation.tailrec
import scala.util.Random

object FairMiner {
  def props(address: ED25519PublicKey, node: Node)(implicit config: PlatformConfig): Props =
    Props(new FairMiner(address, node))

  sealed trait Command
  case class MiningResult(
      blockOpt: Option[Block],
      chainIndex: ChainIndex,
      miningCount: BigInt,
      template: BlockTemplate
  ) extends Command
}

class FairMiner(address: ED25519PublicKey, node: Node)(implicit val config: PlatformConfig)
    extends BaseActor
    with FairMinerState {
  val handlers = node.allHandlers
  val actualMiners: AVector[ActorRef] = AVector.tabulate(config.groups) { to =>
    context.actorOf(ActualMiner.props(ChainIndex(config.mainGroup.value, to)))
  }

  def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      log.info("Start mining")
      initialize()
      context become (handleMining orElse awaitStop)
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      log.info("Stop mining")
      context become awaitStart
  }

  def handleMining: Receive = {
    case FairMiner.MiningResult(blockOpt, chainIndex, miningCount, template) =>
      val to = chainIndex.to.value
      increaseCounts(to, miningCount)
      blockOpt match {
        case Some(block) =>
          val miningCount = getMiningCount(to)
          log.debug(
            s"""MiningCounts: ${(0 until config.groups).map(getMiningCount).mkString(",")}""")
          log.info(
            s"A new block ${block.shortHex} is mined for $chainIndex, miningCount: $miningCount, target: ${block.header.target}")
          val blockHandler = node.allHandlers.getBlockHandler(chainIndex)
          blockHandler ! BlockChainHandler.AddBlocks(AVector(block), Local)
        case None =>
          refreshLastTask(to, template)
      }
    case flowTemplate: BlockFlowTemplate =>
      val blockTemplate = getBlockTemplate(flowTemplate)
      addNewTask(flowTemplate.index.to.value, blockTemplate)
    case AddBlockResult.Success =>
      node.allHandlers.blockHandlers.foreach {
        case (chainIndex, handler) =>
          if (handler == sender()) {
            assert(chainIndex.from == config.mainGroup)
            prepareTemplate(chainIndex.to.value)
          }
      }
    case e: AddBlockResult =>
      log.error(s"Error in adding new block: ${e.toString}")
      context stop self
  }

  def getBlockTemplate(flowTemplate: BlockFlowTemplate): BlockTemplate = {
    // scalastyle:off magic.number
    val transactions = AVector.tabulate(1000)(Transaction.coinbase(address, _))
    // scalastyle:on magic.number
    BlockTemplate(flowTemplate.deps, flowTemplate.target, transactions)
  }

  def prepareTemplate(to: Int): Unit = {
    assert(to >= 0 && to < config.groups)
    val index = ChainIndex(config.mainGroup.value, to)
    handlers.flowHandler ! FlowHandler.PrepareBlockFlow(index)
  }

  def startTask(to: Int, template: BlockTemplate): Unit = {
    actualMiners(to) ! ActualMiner.Task(template)
  }
}

object ActualMiner {
  def props(index: ChainIndex)(implicit config: PlatformConfig): Props =
    Props(new ActualMiner(index))

  sealed trait Command
  case class Task(template: BlockTemplate) extends Command
}

class ActualMiner(index: ChainIndex)(implicit config: PlatformConfig) extends BaseActor {
  import ActualMiner._

  override def receive: Receive = {
    case Task(template) =>
      mine(template) match {
        case Some((block, miningCount)) =>
          context.sender() ! FairMiner.MiningResult(Some(block), index, miningCount, template)
        case None =>
          context.sender() ! FairMiner.MiningResult(None, index, config.nonceStep, template)
      }
  }

  def mine(template: BlockTemplate): Option[(Block, BigInt)] = {
    val nonceStart = BigInt(Random.nextInt(Integer.MAX_VALUE))
    val nonceEnd   = nonceStart + config.nonceStep

    @tailrec
    def iter(current: BigInt): Option[(Block, BigInt)] = {
      if (current < nonceEnd) {
        val header = template.buildHeader(current)
        if (index.validateDiff(header))
          Some((Block(header, template.transactions), current - nonceStart + 1))
        else iter(current + 1)
      } else None
    }
    iter(nonceStart)
  }
}
