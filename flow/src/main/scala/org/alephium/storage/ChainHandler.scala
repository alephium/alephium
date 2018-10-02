package org.alephium.storage

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import org.alephium.network.PeerManager
import org.alephium.protocol.message.{Message, SendBlocks}
import org.alephium.protocol.model.Block
import org.alephium.storage.BlockFlow.ChainIndex
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
          val total       = blockFlow.numBlocks
          val blockNum    = chain.numBlocks
          val height      = chain.maxHeight
          val elapsedTime = System.currentTimeMillis() - block.blockHeader.timestamp
          log.info(
            s"Total: $total; Index: $chainIndex; Height: $height/$blockNum; Time elapsed: ${elapsedTime}ms")
          peerManager ! PeerManager.BroadCast(Message(SendBlocks(blocks)), origin)
        case AddBlockResult.AlreadyExisted =>
          log.info(s"Received already included block")
        case AddBlockResult.MissingDeps(deps) =>
          log.error(s"Missing #${deps.size - 1} deps")
      }
      sender() ! result
  }
}
