package org.alephium.flow.client

import akka.actor.{ActorRef, Props}
import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.storage.{AddBlockResult, BlockChainHandler, BlockFlow}
import org.alephium.flow.storage.FlowHandler.BlockFlowTemplate
import org.alephium.protocol.model.{Block, ChainIndex, Transaction}
import org.alephium.util.{AVector, BaseActor}

import scala.annotation.tailrec
import scala.util.Random
import scala.concurrent.duration._

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

trait FairMinerState {
  implicit def config: PlatformConfig

  def blockFlow: BlockFlow

  private val miningCounts        = Array.fill[BigInt](config.groups)(0)
  private val taskRefreshDuration = 10.seconds.toMillis
  private val taskRefreshTss      = Array.fill[Long](config.groups)(-1)
  private val pendingTasks        = collection.mutable.Map.empty[Int, BlockFlowTemplate]

  def initializeState(): Unit = {
    (0 until config.groups).foreach(refresh)
  }

  def getMiningCount(to: Int): BigInt = miningCounts(to)

  def increaseCounts(to: Int, count: BigInt): Unit = {
    miningCounts(to) += count
  }

  def refresh(to: Int): Unit = {
    assert(to >= 0 && to < config.groups)
    val index    = ChainIndex(config.mainGroup.value, to)
    val template = blockFlow.prepareBlockFlowUnsafe(index)
    taskRefreshTss(to) = System.currentTimeMillis()
    pendingTasks(to)   = template
  }

  def tryToRefresh(to: Int): Unit = {
    val lastRefreshTs = taskRefreshTss(to)
    val currentTs     = System.currentTimeMillis()
    if (currentTs - lastRefreshTs > taskRefreshDuration) {
      refresh(to)
    }
  }

  def removeTask(to: Int): Unit = {
    pendingTasks -= to
  }

  def pickNextTemplate(): (Int, BlockFlowTemplate) = {
    val toTry    = pendingTasks.keys.minBy(miningCounts)
    val template = pendingTasks(toTry)
    (toTry, template)
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
