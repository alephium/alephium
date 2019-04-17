package org.alephium.flow.storage

import akka.actor.{ActorRef, Props}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{PeerManager, TcpHandler}
import org.alephium.protocol.message.{SendBlocks, SendHeaders}
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object BlockChainHandler {
  def props(blockFlow: BlockFlow,
            chainIndex: ChainIndex,
            peerManager: ActorRef,
            flowHandler: ActorRef)(implicit config: PlatformConfig): Props =
    Props(new BlockChainHandler(blockFlow, chainIndex, peerManager, flowHandler))

  sealed trait Command
  case class AddBlocks(blocks: AVector[Block], origin: DataOrigin) extends Command
}

class BlockChainHandler(val blockFlow: BlockFlow,
                        val chainIndex: ChainIndex,
                        peerManager: ActorRef,
                        flowHandler: ActorRef)(implicit val config: PlatformConfig)
    extends BaseActor
    with ChainHandlerLogger {
  val chain: BlockPool = blockFlow.getBlockChain(chainIndex)

  override def receive: Receive = {
    case BlockChainHandler.AddBlocks(blocks, origin) =>
      // TODO: support more blocks later
      assert(blocks.length == 1)
      val block = blocks.head
      handleBlock(block, origin)
  }

  def handleBlock(block: Block, origin: DataOrigin): Unit = {
    if (blockFlow.contains(block)) {
      log.debug(s"Block already exists")
    } else {
      val validationResult = origin match {
        case DataOrigin.LocalMining => Right(())
        case _: DataOrigin.Remote   => blockFlow.validate(block)
      }
      validationResult match {
        case Left(e) =>
          log.debug(s"Failed in block validation: ${e.toString}")
        case Right(_) =>
          logInfo(block.header)
          broadcast(block, origin)
          flowHandler.tell(FlowHandler.AddBlock(block, origin), sender())
      }
    }
  }

  def broadcast(block: Block, origin: DataOrigin): Unit = {
    val blockMessage  = TcpHandler.envelope(SendBlocks(AVector(block)))
    val headerMessage = TcpHandler.envelope(SendHeaders(AVector(block.header)))
    peerManager ! PeerManager.BroadCastBlock(block, blockMessage, headerMessage, origin)
  }
}
