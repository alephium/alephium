package org.alephium.flow.storage

import akka.actor.Props
import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.PeerManager
import org.alephium.protocol.message.{Message, SendBlocks}
import org.alephium.protocol.model.{ChainIndex, PeerId}
import org.alephium.util.{AVector, BaseActor}

object FlowHandler {

  def props(blockFlow: BlockFlow)(implicit config: PlatformConfig): Props =
    Props(new FlowHandler(blockFlow))

  sealed trait Command
  case class GetBlocksAfter(locators: AVector[Keccak256]) extends Command
  case object GetBlockInfo                                extends Command
  case class PrepareSync(peerId: PeerId)                  extends Command
  case class PrepareBlockFlow(chainIndex: ChainIndex)     extends Command

  sealed trait Event
  case class BlockFlowTemplate(deps: AVector[Keccak256], target: BigInt) extends Event
}

// consider single chain for the moment
class FlowHandler(blockFlow: BlockFlow)(implicit config: PlatformConfig) extends BaseActor {
  import FlowHandler._

  override def receive: Receive = {
    case GetBlocksAfter(locators) =>
      val newBlocks = blockFlow.getBlocks(locators)
      sender() ! Message(SendBlocks(newBlocks))
    case GetBlockInfo =>
      sender() ! blockFlow.getBlockInfo
    case PrepareSync(peerId) =>
      // TODO: improve sync algorithm
      val tips = blockFlow.getAllTips
      sender() ! PeerManager.Sync(peerId, tips)
    case PrepareBlockFlow(chainIndex) =>
      val bestDeps    = blockFlow.getBestDeps(chainIndex)
      val singleChain = blockFlow.getBlockChain(chainIndex)
      val target      = singleChain.getHashTarget(bestDeps.getChainHash)
      sender() ! BlockFlowTemplate(bestDeps.deps, target)
  }
}
