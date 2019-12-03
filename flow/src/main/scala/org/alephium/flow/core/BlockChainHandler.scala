package org.alephium.flow.core

import scala.collection.mutable

import akka.actor.{ActorRef, Props}

import org.alephium.crypto.Keccak256
import org.alephium.flow.Utils
import org.alephium.flow.core.validation._
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.message.{Message, SendBlocks, SendHeaders}
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, Forest}

object BlockChainHandler {
  def props(blockFlow: BlockFlow,
            chainIndex: ChainIndex,
            cliqueManager: ActorRef,
            flowHandler: ActorRef)(implicit config: PlatformProfile): Props =
    Props(new BlockChainHandler(blockFlow, chainIndex, cliqueManager, flowHandler))

  def addOneBlock(block: Block, origin: DataOrigin): AddBlocks = {
    val forets = Forest.build[Keccak256, Block](block, _.hash)
    AddBlocks(forets, origin)
  }

  sealed trait Command
  case class AddBlocks(blocks: Forest[Keccak256, Block], origin: DataOrigin) extends Command
  case class AddPendingBlock(block: Block, origin: DataOrigin)               extends Command

  sealed trait Event                              extends ChainHandler.Event
  case class BlocksAdded(chainIndex: ChainIndex)  extends Event
  case object BlocksAddingFailed                  extends Event
  case object InvalidBlocks                       extends Event
  case class FetchSince(tips: AVector[Keccak256]) extends Event
}

class BlockChainHandler(blockFlow: BlockFlow,
                        chainIndex: ChainIndex,
                        cliqueManager: ActorRef,
                        flowHandler: ActorRef)(implicit val config: PlatformProfile)
    extends ChainHandler[Block, BlockStatus](blockFlow, chainIndex, BlockValidation) {
  import BlockChainHandler._

  override def receive: Receive = {
    case AddBlocks(blocks, origin)      => handleDatas(blocks, origin)
    case AddPendingBlock(block, origin) => handlePending(block, origin)
  }

  override def handleMissingParent(blocks: Forest[Keccak256, Block], origin: DataOrigin): Unit = {
    if (origin.isSyncing) {
      log.warning(s"missing parent blocks in syncing, might be DoS attack")
      feedbackAndClear(dataInvalid())
    } else {
      // TODO: DoS prevention
      log.debug(
        s"missing parent blocks, root hashes: ${Utils.showHashableI(blocks.roots.view.map(_.value))}")
      val tips = headerChain.getAllTips
      sender() ! FetchSince(tips)
    }
  }

  override def broadcast(block: Block, origin: DataOrigin): Unit = {
    val blockMessage  = Message.serialize(SendBlocks(AVector(block)))
    val headerMessage = Message.serialize(SendHeaders(AVector(block.header)))
    if (config.brokerInfo.contains(block.chainIndex.from)) {
      cliqueManager ! CliqueManager.BroadCastBlock(block, blockMessage, headerMessage, origin)
    }
  }

  override def addToFlowHandler(block: Block, origin: DataOrigin, sender: ActorRef): Unit = {
    flowHandler.tell(FlowHandler.AddBlock(block, origin), sender)
  }

  override def pendingToFlowHandler(block: Block,
                                    missings: mutable.HashSet[Keccak256],
                                    origin: DataOrigin,
                                    sender: ActorRef,
                                    self: ActorRef): Unit = {
    flowHandler ! FlowHandler.PendingBlock(block, missings, origin, sender, self)
  }

  override def dataAddedEvent(): Event = BlocksAdded(chainIndex)

  override def dataAddingFailed(): Event = BlocksAddingFailed

  override def dataInvalid(): Event = InvalidBlocks
}
