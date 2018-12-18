package org.alephium.flow.storage

import akka.actor.{ActorRef, Props}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{PeerManager, TcpHandler}
import org.alephium.protocol.message.SendHeaders
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object BlockChainHandler {
  def props(blockFlow: BlockFlow, chainIndex: ChainIndex, peerManager: ActorRef)(
      implicit config: PlatformConfig): Props =
    Props(new BlockChainHandler(blockFlow, chainIndex, peerManager))

  sealed trait Command
  case class AddBlocks(blocks: AVector[Block], origin: DataOrigin) extends Command
}

// TODO: investigate concurrency in master branch
class BlockChainHandler(blockFlow: BlockFlow, chainIndex: ChainIndex, peerManager: ActorRef)(
    implicit config: PlatformConfig)
    extends BaseActor {
  val chain: BlockChain = blockFlow.getBlockChain(chainIndex)

  override def receive: Receive = {
    case BlockChainHandler.AddBlocks(blocks, origin) =>
      // TODO: support more blocks later
      assert(blocks.length == 1)
      val block = blocks.head

      val result = blockFlow.add(block)
      result match {
        case AddBlockResult.Success =>
          val total       = blockFlow.numHashes - config.chainNum // exclude genesis blocks
          val elapsedTime = System.currentTimeMillis() - block.blockHeader.timestamp
          log.info(
            s"Index: $chainIndex; Total: $total; ${chain.show(block.hash)}; Time elapsed: ${elapsedTime}ms")
          val headers = blocks.map(_.blockHeader)
          peerManager ! PeerManager.BroadCast(TcpHandler.envelope(SendHeaders(headers)), origin)
        case error: AddBlockResult.Failure =>
          log.warning(s"Failed in adding new block: ${error.toString}")
      }

      sender() ! result
  }
}
