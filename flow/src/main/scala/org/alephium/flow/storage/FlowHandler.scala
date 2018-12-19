package org.alephium.flow.storage

import akka.actor.Props
import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.message.{Message, SendBlocks, SendHeaders}
import org.alephium.protocol.model.{ChainIndex, PeerId}
import org.alephium.util.{AVector, BaseActor}

object FlowHandler {

  def props(blockFlow: BlockFlow)(implicit config: PlatformConfig): Props =
    Props(new FlowHandler(blockFlow))

  sealed trait Command
  case class GetBlocks(hashes: AVector[Keccak256])    extends Command
  case class GetHeaders(hashes: AVector[Keccak256])   extends Command
  case object GetBlockInfo                            extends Command
  case class PrepareSync(peerId: PeerId)              extends Command
  case class PrepareBlockFlow(chainIndex: ChainIndex) extends Command

  sealed trait Event
  case class BlockFlowTemplate(deps: AVector[Keccak256], target: BigInt) extends Event
}

class FlowHandler(blockFlow: BlockFlow)(implicit config: PlatformConfig) extends BaseActor {
  import FlowHandler._

  override def receive: Receive = {
    case GetHeaders(locators) =>
      blockFlow.getHeaders(locators) match {
        case Left(error) =>
          log.warning(s"Failed in getting block headers: $error")
        case Right(headers) =>
          sender() ! Message(SendHeaders(headers))
      }
    case GetBlocks(locators: AVector[Keccak256]) =>
      blockFlow.getBlocks(locators) match {
        case Left(error) =>
          log.warning(s"Failed in getting blocks: $error")
        case Right(blocks) =>
          sender() ! Message(SendBlocks(blocks))
      }
    case GetBlockInfo =>
      sender() ! blockFlow.getBlockInfo
    case PrepareBlockFlow(chainIndex) =>
      prepareBlockFlow(chainIndex)
  }

  def prepareBlockFlow(chainIndex: ChainIndex): Unit = {
    val singleChain = blockFlow.getBlockChain(chainIndex)
    val result = for {
      bestDeps <- blockFlow.getBestDeps(chainIndex)
      target   <- singleChain.getHashTarget(bestDeps.getChainHash)
    } yield BlockFlowTemplate(bestDeps.deps, target)
    result match {
      case Left(error) =>
        log.warning(s"Failed in compute best deps: ${error.toString}")
      case Right(message) =>
        sender() ! message
    }
  }
}
