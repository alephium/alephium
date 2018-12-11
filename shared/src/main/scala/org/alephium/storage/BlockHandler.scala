package org.alephium.storage

import java.net.InetSocketAddress

import akka.actor.Props
import org.alephium.crypto.Keccak256
import org.alephium.network.PeerManager
import org.alephium.protocol.message.{Message, SendBlocks}
import org.alephium.protocol.model.Block
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.util.BaseActor

object BlockHandler {

  def props(blockFlow: BlockFlow): Props =
    Props(new BlockHandler(blockFlow))

  sealed trait Command
  case class AddBlocks(blocks: Seq[Block], origin: BlockOrigin) extends Command
  case class GetBlocksAfter(locators: Seq[Keccak256])           extends Command
  case object GetBlockInfo                                      extends Command
  case class PrepareSync(remote: InetSocketAddress)             extends Command
  case class PrepareBlockFlow(chainIndex: ChainIndex)           extends Command

  sealed trait Event
  case class BlockFlowTemplate(deps: Seq[Keccak256], lastTs: Long) extends Event

  sealed trait BlockOrigin

  object BlockOrigin {
    case object Local                            extends BlockOrigin
    case class Remote(remote: InetSocketAddress) extends BlockOrigin
  }
}

// consider single chain for the moment
class BlockHandler(blockFlow: BlockFlow) extends BaseActor {
  import BlockHandler._

  override def receive: Receive = {
    case GetBlocksAfter(locators) =>
      val newBlocks = blockFlow.getBlocks(locators)
      sender() ! Message(SendBlocks(newBlocks))
    case GetBlockInfo =>
      sender() ! blockFlow.getBlockInfo
    case PrepareSync(remote: InetSocketAddress) =>
      // TODO: improve sync algorithm
      val headers = blockFlow.getAllHeaders
      sender() ! PeerManager.Sync(remote, headers)
    case PrepareBlockFlow(chainIndex) =>
      val (blockHashes, lastTs) = blockFlow.getBestDeps(chainIndex)
      sender() ! BlockFlowTemplate(blockHashes, lastTs)
  }
}
