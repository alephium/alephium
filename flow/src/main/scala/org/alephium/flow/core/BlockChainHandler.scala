package org.alephium.flow.core

import akka.actor.{ActorRef, Props}

import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.message.{Message, SendBlocks, SendHeaders}
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object BlockChainHandler {
  def props(blockFlow: BlockFlow,
            chainIndex: ChainIndex,
            cliqueManager: ActorRef,
            flowHandler: ActorRef)(implicit config: PlatformProfile): Props =
    Props(new BlockChainHandler(blockFlow, chainIndex, cliqueManager, flowHandler))

  sealed trait Command
  case class AddBlock(block: Block, origin: DataOrigin) extends Command
}

class BlockChainHandler(val blockFlow: BlockFlow,
                        val chainIndex: ChainIndex,
                        cliqueManager: ActorRef,
                        flowHandler: ActorRef)(implicit val config: PlatformProfile)
    extends BaseActor
    with ChainHandlerLogger {
  val chain: BlockPool = blockFlow.getBlockChain(chainIndex)

  override def receive: Receive = {
    case BlockChainHandler.AddBlock(block, origin) => handleBlock(block, origin)
  }

  def handleBlock(block: Block, origin: DataOrigin): Unit = {
    if (blockFlow.contains(block)) {
      log.debug(s"Block for ${block.chainIndex} already exists")
    } else {
      val validationResult = origin match {
        case DataOrigin.LocalMining    => Right(ValidBlock)
        case origin: DataOrigin.Remote => blockFlow.validate(block, origin.isSyncing)
      }
      validationResult match {
        case Left(e) =>
          log.debug(s"IO failed in block validation: ${e.toString}")
        case Right(x: InvalidBlockStatus) =>
          log.debug(s"Failed in block validation: $x")
        case Right(_: ValidBlock.type) =>
          logInfo(block.header)
          broadcast(block, origin)
          flowHandler.tell(FlowHandler.AddBlock(block, origin), sender())
      }
    }
  }

  def broadcast(block: Block, origin: DataOrigin): Unit = {
    val blockMessage  = Message.serialize(SendBlocks(AVector(block)))
    val headerMessage = Message.serialize(SendHeaders(AVector(block.header)))
    cliqueManager ! CliqueManager.BroadCastBlock(block, blockMessage, headerMessage, origin)
  }
}
