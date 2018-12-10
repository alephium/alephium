package org.alephium.storage

import akka.actor.{ActorRef, Props}
import org.alephium.network.PeerManager
import org.alephium.protocol.message.{Message, SendBlocks}
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.util.BaseActor

object BlockPoolHandler {
  def props(blockFlow: BlockFlow, chainIndex: ChainIndex, peerManager: ActorRef): Props =
    Props(new BlockPoolHandler(blockFlow, chainIndex, peerManager))
}

// TODO: investigate concurrency in master branch
class BlockPoolHandler(blockFlow: BlockFlow, chainIndex: ChainIndex, peerManager: ActorRef)
    extends BaseActor {
  val blockPool = blockFlow.getPool(chainIndex)

  override def receive: Receive = {
    case BlockHandler.AddBlocks(blocks, origin) =>
      // TODO: support more blocks later
      assert(blocks.length == 1)
      val block = blocks.head
      blockFlow.addBlock(block) match {
        case AddBlockResult.Success =>
          val index    = blockFlow.getIndex(block)
          val blockNum = blockFlow.numBlocks
          //            val length   = blockFlow.getBestLength
          //            val info     = blockFlow.getInfo
          //            log.info(s"Add block for $index, #blocks: $blockNum, #length: $length, info: $info")
          val elapsedTime = System.currentTimeMillis() - block.blockHeader.timestamp
          log.info(s"Index: $index; Blocks: $blockNum; Time elapsed: ${elapsedTime}ms")
          peerManager ! PeerManager.BroadCast(Message(SendBlocks(blocks)), origin)
        case AddBlockResult.AlreadyExisted =>
          log.info(s"Received already included block")
        case AddBlockResult.MissingDeps(deps) =>
          log.error(s"Missing #${deps.size - 1} deps")
      }
  }
}
