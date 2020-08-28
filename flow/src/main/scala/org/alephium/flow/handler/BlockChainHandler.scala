package org.alephium.flow.handler

import scala.collection.mutable

import akka.actor.Props

import org.alephium.flow.core.{BlockFlow, BlockHashChain}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.validation._
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.message.{Message, SendBlocks, SendHeaders}
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{ActorRefT, AVector, Forest}

object BlockChainHandler {
  def props(blockFlow: BlockFlow,
            chainIndex: ChainIndex,
            flowHandler: ActorRefT[FlowHandler.Command])(implicit brokerConfig: BrokerConfig,
                                                         consensusConfig: ConsensusConfig): Props =
    Props(new BlockChainHandler(blockFlow, chainIndex, flowHandler))

  def addOneBlock(block: Block, origin: DataOrigin): AddBlocks = {
    val forets = Forest.build[Hash, Block](block, _.hash)
    AddBlocks(forets, origin)
  }

  sealed trait Command
  final case class AddBlocks(blocks: Forest[Hash, Block], origin: DataOrigin) extends Command
  final case class AddPendingBlock(block: Block,
                                   broker: ActorRefT[ChainHandler.Event],
                                   origin: DataOrigin)
      extends Command

  sealed trait Event                        extends ChainHandler.Event
  final case class BlockAdded(hash: Hash)   extends Event
  case object BlockAddingFailed             extends Event
  final case class InvalidBlock(hash: Hash) extends Event
}

class BlockChainHandler(blockFlow: BlockFlow,
                        chainIndex: ChainIndex,
                        flowHandler: ActorRefT[FlowHandler.Command])(
    implicit brokerConfig: BrokerConfig,
    consensusConfig: ConsensusConfig)
    extends ChainHandler[Block, BlockStatus, BlockChainHandler.Command](blockFlow,
                                                                        chainIndex,
                                                                        BlockValidation.build) {
  import BlockChainHandler._

  val headerChain: BlockHashChain = blockFlow.getHashChain(chainIndex)

  override def receive: Receive = {
    case AddBlocks(blocks, origin) =>
      handleDatas(blocks, ActorRefT[ChainHandler.Event](sender()), origin)
    case AddPendingBlock(block, broker, origin)        => handlePending(block, broker, origin)
    case FlowHandler.BlockAdded(block, broker, origin) => handleDataAdded(block, broker, origin)
  }

  override def broadcast(block: Block, origin: DataOrigin): Unit = {
    val blockMessage  = Message.serialize(SendBlocks(AVector(block)))
    val headerMessage = Message.serialize(SendHeaders(AVector(block.header)))
    if (brokerConfig.contains(block.chainIndex.from)) {
      val event = CliqueManager.BroadCastBlock(block,
                                               blockMessage,
                                               headerMessage,
                                               origin,
                                               blockFlow.isRecent(block))
      publishEvent(event)
    }
  }

  override def addToFlowHandler(block: Block,
                                broker: ActorRefT[ChainHandler.Event],
                                origin: DataOrigin): Unit = {
    flowHandler ! FlowHandler.AddBlock(block, broker, origin)
  }

  override def pendingToFlowHandler(block: Block,
                                    missings: mutable.HashSet[Hash],
                                    broker: ActorRefT[ChainHandler.Event],
                                    origin: DataOrigin,
                                    self: ActorRefT[BlockChainHandler.Command]): Unit = {
    flowHandler ! FlowHandler.PendingBlock(block, missings, origin, broker, self)
  }

  override def dataAddedEvent(data: Block): Event = BlockAdded(data.hash)

  override def dataAddingFailed(): Event = BlockAddingFailed

  override def dataInvalid(data: Block): Event = InvalidBlock(data.hash)
}
