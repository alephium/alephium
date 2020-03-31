package org.alephium.flow.core

import scala.collection.mutable

import akka.actor.Props

import org.alephium.flow.client.Miner
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.message.{Message, SendHeaders}
import org.alephium.protocol.model._
import org.alephium.util._

object FlowHandler {
  def props(blockFlow: BlockFlow, eventBus: ActorRefT[EventBus.Message])(
      implicit config: PlatformConfig): Props =
    Props(new FlowHandler(blockFlow, eventBus))

  sealed trait Command
  final case class AddHeader(header: BlockHeader,
                             broker: ActorRefT[ChainHandler.Event],
                             origin: DataOrigin)
      extends Command
  final case class AddBlock(block: Block, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin)
      extends Command
  final case class GetBlocks(locators: AVector[Hash])        extends Command
  final case class GetHeaders(locators: AVector[Hash])       extends Command
  final case class GetTips(broker: BrokerInfo)               extends Command
  final case class PrepareBlockFlow(chainIndex: ChainIndex)  extends Command
  final case class Register(miner: ActorRefT[Miner.Command]) extends Command
  case object UnRegister                                     extends Command

  sealed trait PendingData {
    def missingDeps: mutable.HashSet[Hash]
  }
  final case class PendingBlock(block: Block,
                                missingDeps: mutable.HashSet[Hash],
                                origin: DataOrigin,
                                broker: ActorRefT[ChainHandler.Event],
                                chainHandler: ActorRefT[BlockChainHandler.Command])
      extends PendingData
      with Command
  final case class PendingHeader(header: BlockHeader,
                                 missingDeps: mutable.HashSet[Hash],
                                 origin: DataOrigin,
                                 broker: ActorRefT[ChainHandler.Event],
                                 chainHandler: ActorRefT[HeaderChainHandler.Command])
      extends PendingData
      with Command

  sealed trait Event
  final case class BlockFlowTemplate(index: ChainIndex,
                                     deps: AVector[Hash],
                                     target: BigInt,
                                     transactions: AVector[Transaction])
      extends Event
  final case class CurrentTips(tips: AVector[Hash])      extends Event
  final case class BlocksLocated(blocks: AVector[Block]) extends Event
  final case class BlockAdded(block: Block,
                              broker: ActorRefT[ChainHandler.Event],
                              origin: DataOrigin)
      extends Event
  final case class HeaderAdded(header: BlockHeader,
                               broker: ActorRefT[ChainHandler.Event],
                               origin: DataOrigin)
      extends Event
  final case class BlockNotify(header: BlockHeader, height: Int) extends EventBus.Event
}

// TODO: set AddHeader and AddBlock with highest priority
// Queue all the work related to miner, rpc server, etc. in this actor
class FlowHandler(blockFlow: BlockFlow, eventBus: ActorRefT[EventBus.Message])(
    implicit config: PlatformConfig)
    extends BaseActor
    with FlowHandlerState {
  import FlowHandler._

  override def statusSizeLimit: Int = config.brokerNum * 8

  override def receive: Receive = handleWith(None)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def handleWith(minerOpt: Option[ActorRefT[Miner.Command]]): Receive = {
    case GetHeaders(locators) =>
      blockFlow.getHeaders(locators) match {
        case Left(error) =>
          log.warning(s"Failure while getting block headers: $error")
        case Right(headers) =>
          sender() ! Message(SendHeaders(headers))
      }
    case GetBlocks(locators: AVector[Hash]) =>
      locators.flatMapE(blockFlow.getBlocksAfter) match {
        case Left(error) =>
          log.warning(s"IO Failure while getting blocks: $error")
        case Right(blocks) =>
          sender() ! BlocksLocated(blocks)
      }
    case PrepareBlockFlow(chainIndex) => prepareBlockFlow(chainIndex)
    case AddHeader(header, broker, origin: DataOrigin) =>
      handleHeader(minerOpt, header, broker, origin)
    case AddBlock(block, broker, origin) =>
      handleBlock(minerOpt, block, broker, origin)
    case pending: PendingData => handlePending(pending)
    case GetTips(brokerInfo)  => sender() ! CurrentTips(blockFlow.getOutBlockTips(brokerInfo))
    case Register(miner)      => context become handleWith(Some(miner))
    case UnRegister           => context become handleWith(None)
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

  def handleHeader(minerOpt: Option[ActorRefT[Miner.Command]],
                   header: BlockHeader,
                   broker: ActorRefT[ChainHandler.Event],
                   origin: DataOrigin): Unit = {
    if (!blockFlow.contains(header)) {
      blockFlow.add(header) match {
        case Left(e) =>
          // TODO: handle IOError
          log.error(s"Failed in adding new header: ${e.toString}")
        case Right(_) =>
          sender() ! FlowHandler.HeaderAdded(header, broker, origin)
          updateUponNewData(header.hash)
          minerOpt.foreach(_ ! Miner.UpdateTemplate)
          logInfo(header)
      }
    }
  }

  def handleBlock(minerOpt: Option[ActorRefT[Miner.Command]],
                  block: Block,
                  broker: ActorRefT[ChainHandler.Event],
                  origin: DataOrigin): Unit = {
    if (!blockFlow.contains(block)) {
      blockFlow.add(block) match {
        case Left(e) =>
          // TODO: handle IOError
          log.error(s"Failed in adding new block: ${e.toString}")
        case Right(_) =>
          sender() ! FlowHandler.BlockAdded(block, broker, origin)
          updateUponNewData(block.hash)
          origin match {
            case DataOrigin.Local =>
              minerOpt.foreach(_ ! Miner.MinedBlockAdded(block.chainIndex))
            case _: DataOrigin.FromClique =>
              minerOpt.foreach(_ ! Miner.UpdateTemplate)
          }
          eventBus ! BlockNotify(block.header, blockFlow.getHeight(block))
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

  def updateUponNewData(hash: Hash): Unit = {
    val readies = updateStatus(hash)
    if (readies.nonEmpty) {
      log.debug(s"There are #${readies.size} pending blocks/header ready for further processing")
    }
    readies.foreach(feedback)
  }

  def feedback(pending: PendingData): Unit = pending match {
    case PendingBlock(block, _, origin, broker, chainHandler) =>
      chainHandler ! BlockChainHandler.AddPendingBlock(block, broker, origin)
    case PendingHeader(header, _, origin, broker, chainHandler) =>
      chainHandler ! HeaderChainHandler.AddPendingHeader(header, broker, origin)
  }

  def logInfo(header: BlockHeader): Unit = {
    val total = blockFlow.numHashes - config.chainNum // exclude genesis blocks
    val index = header.chainIndex
    val chain = blockFlow.getHeaderChain(header)
    val heights = for {
      i <- 0 until config.groups
      j <- 0 until config.groups
      height = blockFlow.getHashChain(ChainIndex.unsafe(i, j)).maxHeight
    } yield s"$i-$j:$height"
    val heightsInfo = heights.mkString(", ")
    val targetRatio = (BigDecimal(header.target) / BigDecimal(config.maxMiningTarget)).toFloat
    val timeSpan = {
      val parentHash = chain.getPredecessor(header.hash, chain.getHeight(header) - 1)
      chain.getBlockHeader(parentHash) match {
        case Left(_) => "???ms"
        case Right(parentHeader) =>
          val span = header.timestamp.millis - parentHeader.timestamp.millis
          s"${span}ms"
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

  def updateStatus(hash: Hash): IndexedSeq[PendingData] = {
    val toRemove: IndexedSeq[Int] = pendingStatus.collect {
      case (ts, status) if status.missingDeps.remove(hash) && status.missingDeps.isEmpty =>
        ts
    }(scala.collection.breakOut)
    val blocks = toRemove.map(pendingStatus(_))
    toRemove.foreach(pendingStatus.remove)
    blocks
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def checkSizeLimit(): Unit = {
    if (pendingStatus.size > statusSizeLimit) {
      val toRemove = pendingStatus.head._1
      pendingStatus.remove(toRemove)
      ()
    }
  }
}
