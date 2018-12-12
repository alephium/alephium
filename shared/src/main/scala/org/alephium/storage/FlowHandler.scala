package org.alephium.storage

import java.net.InetSocketAddress

import akka.actor.Props
import org.alephium.crypto.Keccak256
import org.alephium.network.PeerManager
import org.alephium.protocol.message.{Message, SendBlocks}
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.util.BaseActor

object FlowHandler {

  def props(blockFlow: BlockFlow): Props =
    Props(new FlowHandler(blockFlow))

  sealed trait Command
  case class GetBlocksAfter(locators: Seq[Keccak256]) extends Command
  case object GetBlockInfo                            extends Command
  case class PrepareSync(remote: InetSocketAddress)   extends Command
  case class PrepareBlockFlow(chainIndex: ChainIndex) extends Command

  sealed trait Event
  case class BlockFlowTemplate(deps: Seq[Keccak256], lastTs: Long) extends Event
}

// consider single chain for the moment
class FlowHandler(blockFlow: BlockFlow) extends BaseActor {
  import FlowHandler._

  override def receive: Receive = {
    case GetBlocksAfter(locators) =>
      val newBlocks = blockFlow.getBlocks(locators)
      sender() ! Message(SendBlocks(newBlocks))
    case GetBlockInfo =>
      sender() ! blockFlow.getBlockInfo
    case PrepareSync(remote: InetSocketAddress) =>
      // TODO: improve sync algorithm
      val tips = blockFlow.getAllTips
      sender() ! PeerManager.Sync(remote, tips)
    case PrepareBlockFlow(chainIndex) =>
      val (blockHashes, lastTs) = blockFlow.getBestDeps(chainIndex)
      sender() ! BlockFlowTemplate(blockHashes, lastTs)
  }
}
