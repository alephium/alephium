package org.alephium.flow.core

import scala.collection.mutable

import akka.actor.{ActorRef, Props}

import org.alephium.crypto.Keccak256
import org.alephium.flow.client.Miner
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.message.{Message, SendHeaders}
import org.alephium.protocol.model._
import org.alephium.util._

object FlowHandler {
  def props(blockFlow: BlockFlow)(implicit config: PlatformProfile): Props =
    Props(new FlowHandler(blockFlow))

  sealed trait Command
  case class GetBlocks(locators: AVector[Keccak256])    extends Command
  case class GetHeaders(locators: AVector[Keccak256])   extends Command
  case class GetTips(broker: BrokerInfo)                extends Command
  case class PrepareBlockFlow(chainIndex: ChainIndex)   extends Command
  case class AddHeader(header: BlockHeader)             extends Command
  case class AddBlock(block: Block, origin: DataOrigin) extends Command
  case class Register(miner: ActorRef)                  extends Command
  case object UnRegister                                extends Command

  sealed trait PendingData {
    def missingDeps: mutable.HashSet[Keccak256]
  }
  case class PendingBlock(block: Block,
                          missingDeps: mutable.HashSet[Keccak256],
                          origin: DataOrigin,
                          broker: ActorRef,
                          chainHandler: ActorRef)
      extends PendingData
      with Command
  case class PendingHeader(header: BlockHeader,
                           missingDeps: mutable.HashSet[Keccak256],
                           origin: DataOrigin,
                           broker: ActorRef,
                           chainHandler: ActorRef)
      extends PendingData
      with Command

  sealed trait Event
  case class BlockFlowTemplate(index: ChainIndex,
                               deps: AVector[Keccak256],
                               target: BigInt,
                               transactions: AVector[Transaction])
      extends Event
  case class CurrentTips(tips: AVector[Keccak256]) extends Event
  case class BlocksLocated(blocks: AVector[Block]) extends Event
}

// TODO: set AddHeader and AddBlock with highest priority
// Queue all the work related to miner, rpc server, etc. in this actor
class FlowHandler(blockFlow: BlockFlow)(implicit config: PlatformProfile)
    extends BaseActor
    with FlowHandlerState {
  import FlowHandler._

  override def statusSizeLimit: Int = config.brokerNum * 8

  override def receive: Receive = handleWith(None)

  def handleWith(minerOpt: Option[ActorRef]): Receive = {
    case GetHeaders(locators) =>
      blockFlow.getHeaders(locators) match {
        case Left(error) =>
          log.warning(s"Failure while getting block headers: $error")
        case Right(headers) =>
          sender() ! Message(SendHeaders(headers))
      }
    case GetBlocks(locators: AVector[Keccak256]) =>
      locators.flatMapE(blockFlow.getBlocksAfter) match {
        case Left(error) =>
          log.warning(s"IO Failure while getting blocks: $error")
        case Right(blocks) =>
          sender() ! BlocksLocated(blocks)
      }
    case PrepareBlockFlow(chainIndex)   => prepareBlockFlow(chainIndex)
    case AddHeader(header: BlockHeader) => handleHeader(minerOpt, header)
    case AddBlock(block, origin)        => handleBlock(minerOpt, block, origin)
    case pending: PendingData           => handlePending(pending)
    case GetTips(brokerInfo)            => sender() ! CurrentTips(blockFlow.getOutBlockTips(brokerInfo))
    case Register(miner)                => context become handleWith(Some(miner))
    case UnRegister                     => context become handleWith(None)
  }

  def prepareBlockFlow(chainIndex: ChainIndex): Unit = {
    assert(config.brokerInfo.contains(chainIndex.from))
    val template = blockFlow.prepareBlockFlow(chainIndex)
    template match {
      case Left(error) =>
        log.warning(s"Failure while computing best dependencies: ${error.toString}")
      case Right(message) =>
        sender() ! message
    }
  }

  def handleHeader(minerOpt: Option[ActorRef], header: BlockHeader): Unit = {
    if (!blockFlow.contains(header)) {
      blockFlow.add(header) match {
        case Left(e) =>
          // TODO: handle IOError
          log.error(s"Failed in adding new header: ${e.toString}")
        case Right(_) =>
          updateUponNewData(header.hash)
          minerOpt.foreach(_ ! Miner.UpdateTemplate)
          logInfo(header)
      }
    }
  }

  def handleBlock(minerOpt: Option[ActorRef], block: Block, origin: DataOrigin): Unit = {
    if (!blockFlow.contains(block)) {
      blockFlow.add(block) match {
        case Left(e) =>
          // TODO: handle IOError
          log.error(s"Failed in adding new block: ${e.toString}")
        case Right(_) =>
          updateUponNewData(block.hash)
          origin match {
            case DataOrigin.LocalMining =>
              minerOpt.foreach(_ ! Miner.MinedBlockAdded(block.chainIndex))
            case _: DataOrigin.Remote =>
              minerOpt.foreach(_ ! Miner.UpdateTemplate)
          }
          logInfo(block.header)
      }
    }
  }

  def handlePending(pending: PendingData): Unit = {
    val missings = pending.missingDeps
    missings.retain(!blockFlow.contains(_)) // necessary to remove the new added dependencies
    if (missings.isEmpty) {
      feedback(pending)
    } else {
      addStatus(pending)
    }
  }

  def updateUponNewData(hash: Keccak256): Unit = {
    val readies = updateStatus(hash)
    if (readies.nonEmpty) {
      log.debug(s"There are #${readies.size} pending blocks/header ready for further processing")
    }
    readies.foreach(feedback)
  }

  def feedback(pending: PendingData): Unit = pending match {
    case PendingBlock(block, _, origin, broker, chainHandler) =>
      chainHandler.tell(BlockChainHandler.AddPendingBlock(block, origin), broker)
    case PendingHeader(header, _, origin, broker, chainHandler) =>
      chainHandler.tell(HeaderChainHandler.AddPendingHeader(header, origin), broker)
  }

  def logInfo(header: BlockHeader): Unit = {
    val total = blockFlow.numHashes - config.chainNum // exclude genesis blocks
    val index = header.chainIndex
    val chain = blockFlow.getHeaderChain(header)
    val heights = for {
      i <- 0 until config.groups
      j <- 0 until config.groups
      height = blockFlow.getHashChain(ChainIndex(i, j)).maxHeight
    } yield s"$i-$j:$height"
    val heightsInfo = heights.mkString(", ")
    val targetRatio = (BigDecimal(header.target) / BigDecimal(config.maxMiningTarget)).toFloat
    val timeSpan = {
      val parentHash = chain.getPredecessor(header.hash, chain.getHeight(header) - 1)
      chain.getBlockHeader(parentHash) match {
        case Left(_) => "??? seconds"
        case Right(parentHeader) =>
          val span = header.timestamp.diff(parentHeader.timestamp)
          s"${span.toSeconds} seconds"
      }
    }
    log.info(s"$index; total: $total; ${chain
      .show(header.hash)}; heights: $heightsInfo; targetRatio: $targetRatio, timeSpan: $timeSpan")
  }
}

trait FlowHandlerState {
  import FlowHandler._

  def statusSizeLimit: Int

  var counter: Int  = 0
  val pendingStatus = scala.collection.mutable.SortedMap.empty[Int, PendingData]

  def increaseAndCounter(): Int = {
    counter += 1
    counter
  }

  def addStatus(pending: PendingData): Unit = {
    pendingStatus.put(increaseAndCounter(), pending)
    checkSizeLimit()
  }

  def updateStatus(hash: Keccak256): IndexedSeq[PendingData] = {
    val toRemove: IndexedSeq[Int] = pendingStatus.collect {
      case (ts, status) if status.missingDeps.remove(hash) && status.missingDeps.isEmpty =>
        ts
    }(scala.collection.breakOut)
    val blocks = toRemove.map(pendingStatus(_))
    toRemove.foreach(pendingStatus.remove)
    blocks
  }

  def checkSizeLimit(): Unit = {
    if (pendingStatus.size > statusSizeLimit) {
      val toRemove = pendingStatus.head._1
      pendingStatus.remove(toRemove)
      ()
    }
  }
}
