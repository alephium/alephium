package org.alephium.flow.storage

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import org.alephium.flow.constant.Network
import org.alephium.flow.model.ChainIndex
import org.alephium.flow.network.PeerManager
import org.alephium.protocol.message.{Message, SendBlocks}
import org.alephium.protocol.model.Block
import org.alephium.util.BaseActor

object ChainHandler {
  def props(blockFlow: BlockFlow, chainIndex: ChainIndex, peerManager: ActorRef): Props =
    Props(new ChainHandler(blockFlow, chainIndex, peerManager))

  sealed trait Command
  case class AddBlocks(blocks: Seq[Block], origin: BlockOrigin) extends Command

  sealed trait BlockOrigin

  object BlockOrigin {
    case object Local                            extends BlockOrigin
    case class Remote(remote: InetSocketAddress) extends BlockOrigin
  }
}

// TODO: investigate concurrency in master branch
class ChainHandler(blockFlow: BlockFlow, chainIndex: ChainIndex, peerManager: ActorRef)
    extends BaseActor {
  val chain: SingleChain = blockFlow.getChain(chainIndex)

  override def receive: Receive = {
    case ChainHandler.AddBlocks(blocks, origin) =>
      // TODO: support more blocks later
      assert(blocks.length == 1)
      val block = blocks.head

      val result = blockFlow.add(block)
      result match {
        case AddBlockResult.Success =>
          val total       = blockFlow.numBlocks - Network.chainNum // exclude genesis blocks
          val elapsedTime = System.currentTimeMillis() - block.blockHeader.timestamp
          log.info(
            s"Index: $chainIndex; Total: $total; ${chain.show(block)}; Time elapsed: ${elapsedTime}ms")
          peerManager ! PeerManager.BroadCast(Message(SendBlocks(blocks)), origin)
        case error: AddBlockResult.Failure =>
          log.info(s"Failed in adding new block: $error")
      }

      sender() ! result
  }
}
