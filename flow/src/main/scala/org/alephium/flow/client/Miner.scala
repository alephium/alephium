package org.alephium.flow.client

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{Failure, Random, Success}

import akka.actor.Props
import akka.util.ByteString

import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.core.{AllHandlers, BlockChainHandler, BlockFlow, FlowHandler}
import org.alephium.flow.core.validation.Validation
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model._
import org.alephium.protocol.script.PayTo
import org.alephium.util.{ActorRefT, AVector, BaseActor}

object Miner {
  def props(node: Node)(implicit config: PlatformConfig): Props =
    props(node.blockFlow, node.allHandlers)

  def props(blockFlow: BlockFlow, allHandlers: AllHandlers)(
      implicit config: PlatformConfig): Props = {
    val addresses = AVector.tabulate(config.groups) { i =>
      val index          = GroupIndex.unsafe(i)
      val (_, publicKey) = index.generateKey(PayTo.PKH)
      publicKey
    }
    props(addresses, blockFlow, allHandlers)
  }

  def props(addresses: AVector[ED25519PublicKey], blockFlow: BlockFlow, allHandlers: AllHandlers)(
      implicit config: PlatformConfig): Props = {
    require(addresses.length == config.groups)
    addresses.foreachWithIndex { (address, i) =>
      require(GroupIndex.from(PayTo.PKH, address).value == i)
    }
    Props(new Miner(addresses, blockFlow, allHandlers))
  }

  sealed trait Command
  case object Start                                   extends Command
  case object Stop                                    extends Command
  case object UpdateTemplate                          extends Command
  final case class MinedBlockAdded(index: ChainIndex) extends Command
  final case class MiningResult(blockOpt: Option[Block],
                                chainIndex: ChainIndex,
                                miningCount: BigInt)
      extends Command

  def mine(index: ChainIndex, template: BlockTemplate)(
      implicit config: PlatformConfig): Option[(Block, BigInt)] = {
    val nonceStart = BigInt(Random.nextInt(Integer.MAX_VALUE))
    val nonceEnd   = nonceStart + config.nonceStep

    @tailrec
    def iter(current: BigInt): Option[(Block, BigInt)] = {
      if (current < nonceEnd) {
        val header = template.buildHeader(current)
        if (Validation.validateMined(header, index))
          Some((Block(header, template.transactions), current - nonceStart + 1))
        else iter(current + 1)
      } else None
    }
    iter(nonceStart)
  }
}

class Miner(addresses: AVector[ED25519PublicKey], blockFlow: BlockFlow, allHandlers: AllHandlers)(
    implicit val config: PlatformConfig)
    extends BaseActor
    with MinerState {
  val handlers = allHandlers

  def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      log.info("Start mining")
      handlers.flowHandler ! FlowHandler.Register(ActorRefT[Miner.Command](self))
      updateTasks()
      startNewTasks()
      context become (handleMining orElse awaitStop)
    case _: Miner.Command =>
      ()
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      log.info("Stop mining")
      handlers.flowHandler ! FlowHandler.UnRegister
      context become awaitStart
  }

  def handleMining: Receive = {
    case Miner.MiningResult(blockOpt, chainIndex, miningCount) =>
      assert(config.brokerInfo.contains(chainIndex.from))
      val fromShift = chainIndex.from.value - config.brokerInfo.groupFrom
      val to        = chainIndex.to.value
      increaseCounts(fromShift, to, miningCount)
      blockOpt match {
        case Some(block) =>
          val miningCount = getMiningCount(fromShift, to)
          val txCount     = block.transactions.length
          log.debug(s"MiningCounts: $countsToString")
          log.info(
            s"A new block ${block.shortHex} got mined for $chainIndex, tx: $txCount, miningCount: $miningCount, target: ${block.header.target}")
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

  private def coinbase(to: Int): Transaction = {
    Transaction.coinbase(addresses(to), ByteString.fromInts(Random.nextInt()))
  }

  def prepareTemplate(fromShift: Int, to: Int): BlockTemplate = {
    assume(0 <= fromShift && fromShift < config.groupNumPerBroker && 0 <= to && to < config.groups)
    val index        = ChainIndex.unsafe(config.brokerInfo.groupFrom + fromShift, to)
    val flowTemplate = blockFlow.prepareBlockFlowUnsafe(index)
    BlockTemplate(flowTemplate.deps, flowTemplate.target, flowTemplate.transactions :+ coinbase(to))
  }

  def startTask(fromShift: Int,
                to: Int,
                template: BlockTemplate,
                blockHandler: ActorRefT[BlockChainHandler.Command]): Unit = {
    val task = Future {
      val index = ChainIndex.unsafe(fromShift + config.brokerInfo.groupFrom, to)
      Miner.mine(index, template) match {
        case Some((block, miningCount)) =>
          val handlerMessage = BlockChainHandler.addOneBlock(block, Local)
          blockHandler ! handlerMessage
          self ! Miner.MiningResult(Some(block), index, miningCount)
        case None =>
          self ! Miner.MiningResult(None, index, config.nonceStep)
      }
    }(context.dispatcher)
    task.onComplete {
      case Success(_) => ()
      case Failure(e) => log.debug("Mining task failed", e)
    }(context.dispatcher)
  }
}
