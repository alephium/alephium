package org.alephium.flow.core

import scala.collection.mutable

import akka.actor.{ActorRef, Props}

import org.alephium.crypto.Keccak256
import org.alephium.flow.Utils
import org.alephium.flow.core.FlowHandler.BlockAdded
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
  case class AddBlocks(blocks: Forest[Keccak256, Block], origin: DataOrigin)     extends Command
  case class AddPendingBlock(block: Block, broker: ActorRef, origin: DataOrigin) extends Command

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

  val headerChain = blockFlow.getHashChain(chainIndex)

  override def receive: Receive = {
    case AddBlocks(blocks, origin)              => handleDatas(blocks, sender(), origin)
    case AddPendingBlock(block, broker, origin) => handlePending(block, broker, origin)
    case BlockAdded(block, broker, origin)      => handleDataAdded(block, broker, origin)
  }

  override def handleMissingParent(blocks: Forest[Keccak256, Block],
                                   broker: ActorRef,
                                   origin: DataOrigin): Unit = {
    if (origin.isSyncing) {
      log.warning(s"missing parent blocks in syncing, might be DoS attack")
      feedbackAndClear(broker, dataInvalid())
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

  override def addToFlowHandler(block: Block, broker: ActorRef, origin: DataOrigin): Unit = {
    flowHandler ! FlowHandler.AddBlock(block, broker, origin)
  }

  override def pendingToFlowHandler(block: Block,
                                    missings: mutable.HashSet[Keccak256],
                                    broker: ActorRef,
                                    origin: DataOrigin,
                                    self: ActorRef): Unit = {
    flowHandler ! FlowHandler.PendingBlock(block, missings, origin, broker, self)
  }

  override def dataAddedEvent(): Event = BlocksAdded(chainIndex)

  override def dataAddingFailed(): Event = BlocksAddingFailed

  override def dataInvalid(): Event = InvalidBlocks
}
