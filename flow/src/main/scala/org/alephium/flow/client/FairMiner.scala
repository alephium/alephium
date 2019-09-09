package org.alephium.flow.client

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{Failure, Random, Success}

import akka.actor.{ActorRef, Props}
import akka.util.ByteString

import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.LocalMining
import org.alephium.flow.storage.{BlockChainHandler, FlowHandler}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, BaseActor}

object FairMiner {
  def props(node: Node)(implicit config: PlatformConfig): Props = {
    val addresses = AVector.tabulate(config.groups) { i =>
      val index          = GroupIndex(i)
      val (_, publicKey) = GroupConfig.generateKeyForGroup(index)
      publicKey
    }
    props(addresses, node)
  }

  def props(addresses: AVector[ED25519PublicKey], node: Node)(
      implicit config: PlatformConfig): Props = {
    require(addresses.length == config.groups)
    addresses.foreachWithIndex { (address, i) =>
      require(GroupIndex.from(address).value == i)
    }
    Props(new FairMiner(addresses, node))
  }

  sealed trait Command
  case class MiningResult(blockOpt: Option[Block], chainIndex: ChainIndex, miningCount: BigInt)
      extends Command

  def mine(index: ChainIndex, template: BlockTemplate)(
      implicit config: PlatformConfig): Option[(Block, BigInt)] = {
    val nonceStart = BigInt(Random.nextInt(Integer.MAX_VALUE))
    val nonceEnd   = nonceStart + config.nonceStep

    @tailrec
    def iter(current: BigInt): Option[(Block, BigInt)] = {
      if (current < nonceEnd) {
        val header = template.buildHeader(current)
        if (header.preValidate(index))
          Some((Block(header, template.transactions), current - nonceStart + 1))
        else iter(current + 1)
      } else None
    }
    iter(nonceStart)
  }
}

class FairMiner(addresses: AVector[ED25519PublicKey], node: Node)(
    implicit val config: PlatformConfig)
    extends BaseActor
    with FairMinerState {
  val handlers = node.allHandlers
  handlers.flowHandler ! FlowHandler.Register(self)

  def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      log.info("Start mining")
      startNewTasks()
      context become (handleMining orElse awaitStop)
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      log.info("Stop mining")
      context become awaitStart
  }

  def handleMining: Receive = {
    case FairMiner.MiningResult(blockOpt, chainIndex, miningCount) =>
      assert(config.brokerInfo.contains(chainIndex.from))
      val fromShift = chainIndex.from.value - config.brokerInfo.groupFrom
      val to        = chainIndex.to.value
      increaseCounts(fromShift, to, miningCount)
      blockOpt match {
        case Some(block) =>
          val miningCount = getMiningCount(fromShift, to)
          log.debug(s"MiningCounts: $countsToString")
          log.info(
            s"A new block ${block.shortHex} got mined for $chainIndex, miningCount: $miningCount, target: ${block.header.target}")
        case None =>
          setIdle(fromShift, to)
          startNewTasks()
      }
    case Miner.UpdateTemplate =>
      updateTasks()
    case Miner.MinedBlockAdded(chainIndex) =>
      val fromShift = chainIndex.from.value - config.brokerInfo.groupFrom
      val to        = chainIndex.to.value
      updateTasks()
      setIdle(fromShift, to)
      startNewTasks()
  }

  private val fakeTransactions = AVector.tabulate(config.groups) { to =>
    val address = addresses(to)
    val data    = ByteString.fromInts(Random.nextInt())
    // scalastyle:off magic.number
    val transactions = AVector.tabulate(1)(Transaction.coinbase(address, _, data))
    // scalastyle:on magic.number
    transactions
  }

  def prepareTemplate(fromShift: Int, to: Int): BlockTemplate = {
    assert(0 <= fromShift && fromShift < config.groupNumPerBroker && 0 <= to && to < config.groups)
    val index        = ChainIndex(config.brokerInfo.groupFrom + fromShift, to)
    val flowTemplate = node.blockFlow.prepareBlockFlowUnsafe(index)
    BlockTemplate(flowTemplate.deps, flowTemplate.target, fakeTransactions(to))
  }

  def startTask(fromShift: Int, to: Int, template: BlockTemplate, blockHandler: ActorRef): Unit = {
    val task = Future {
      val index = ChainIndex.unsafe(fromShift + config.brokerInfo.groupFrom, to)
      FairMiner.mine(index, template) match {
        case Some((block, miningCount)) =>
          val handlerMessage = BlockChainHandler.AddBlocks(AVector(block), LocalMining)
          blockHandler ! handlerMessage
          self ! FairMiner.MiningResult(Some(block), index, miningCount)
        case None =>
          self ! FairMiner.MiningResult(None, index, config.nonceStep)
      }
    }(context.dispatcher)
    task.onComplete {
      case Success(_) => ()
      case Failure(e) => log.debug("Mining task failed", e)
    }(context.dispatcher)
  }
}
