package org.alephium.flow.storage

import akka.actor.{ActorRef, Props}
import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.client.Miner
import org.alephium.flow.model.DataOrigin
import org.alephium.protocol.message.{Message, SendBlocks, SendHeaders}
import org.alephium.protocol.model.{Block, BlockHeader, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object FlowHandler {

  def props(blockFlow: BlockFlow)(implicit config: PlatformConfig): Props =
    Props(new FlowHandler(blockFlow))

  sealed trait Command
  case class GetBlocks(hashes: AVector[Keccak256])      extends Command
  case class GetHeaders(hashes: AVector[Keccak256])     extends Command
  case class PrepareBlockFlow(chainIndex: ChainIndex)   extends Command
  case class AddHeader(header: BlockHeader)             extends Command
  case class AddBlock(block: Block, origin: DataOrigin) extends Command
  case class Register(miner: ActorRef)                  extends Command

  sealed trait Event
  case class BlockFlowTemplate(index: ChainIndex, deps: AVector[Keccak256], target: BigInt)
      extends Event
}

// Queue all the work related to miner, rpc server, etc. in this actor
class FlowHandler(blockFlow: BlockFlow)(implicit config: PlatformConfig) extends BaseActor {
  import FlowHandler._

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
      blockFlow.getBlocks(locators) match {
        case Left(error) =>
          log.warning(s"Failure while getting blocks: $error")
        case Right(blocks) =>
          sender() ! Message(SendBlocks(blocks))
      }
    case PrepareBlockFlow(chainIndex) =>
      prepareBlockFlow(chainIndex)
    case AddHeader(header: BlockHeader) =>
      handleHeader(minerOpt, header)
    case AddBlock(block, origin) =>
      handleBlock(minerOpt, block, origin)
    case Register(miner) =>
      context become handleWith(Some(miner))
  }

  def prepareBlockFlow(chainIndex: ChainIndex): Unit = {
    assert(config.brokerId.contains(chainIndex.from))
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

  def logInfo(header: BlockHeader): Unit = {
    val total = blockFlow.numHashes - config.chainNum // exclude genesis blocks
    val index = header.chainIndex
    val chain = blockFlow.getHeaderChain(header)
    val utxos = blockFlow.numUTXOs
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
          val span = (header.timestamp - parentHeader.timestamp).toDouble / 1000
          s"$span seconds"
      }
    }
    log.info(s"$index; total: $total; utxos: $utxos; ${chain
      .show(header.hash)}; heights: $heightsInfo; targetRatio: $targetRatio, timeSpan: $timeSpan")
  }
}
