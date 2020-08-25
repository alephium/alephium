package org.alephium.flow.handler

import scala.collection.mutable

import akka.actor.Props

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.FlowHandler.HeaderAdded
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.validation._
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.model.{BlockHeader, ChainIndex}
import org.alephium.util.{ActorRefT, Forest}

object HeaderChainHandler {
  def props(blockFlow: BlockFlow,
            chainIndex: ChainIndex,
            flowHandler: ActorRefT[FlowHandler.Command])(implicit brokerConfig: BrokerConfig,
                                                         consensusConfig: ConsensusConfig): Props =
    Props(new HeaderChainHandler(blockFlow, chainIndex, flowHandler))

  def addOneHeader(header: BlockHeader, origin: DataOrigin): AddHeaders = {
    val forest = Forest.build[Hash, BlockHeader](header, _.hash)
    AddHeaders(forest, origin)
  }

  sealed trait Command
  final case class AddHeaders(header: Forest[Hash, BlockHeader], origin: DataOrigin) extends Command
  final case class AddPendingHeader(header: BlockHeader,
                                    broker: ActorRefT[ChainHandler.Event],
                                    origin: DataOrigin)
      extends Command

  sealed trait Event                                    extends ChainHandler.Event
  final case class HeadersAdded(chainIndex: ChainIndex) extends Event
  case object HeadersAddingFailed                       extends Event
  case object InvalidHeaders                            extends Event
}

class HeaderChainHandler(blockFlow: BlockFlow,
                         chainIndex: ChainIndex,
                         flowHandler: ActorRefT[FlowHandler.Command])(
    implicit brokerConfig: BrokerConfig,
    consensusConfig: ConsensusConfig)
    extends ChainHandler[BlockHeader, HeaderStatus, HeaderChainHandler.Command](
      blockFlow,
      chainIndex,
      HeaderValidation.build) {
  import HeaderChainHandler._

  override def receive: Receive = {
    case AddHeaders(headers, origin) =>
      handleDatas(headers, ActorRefT[ChainHandler.Event](sender()), origin)
    case AddPendingHeader(header, broker, origin) => handlePending(header, broker, origin)
    case HeaderAdded(header, broker, origin)      => handleDataAdded(header, broker, origin)
  }

  override def broadcast(header: BlockHeader, origin: DataOrigin): Unit = ()

  override def addToFlowHandler(header: BlockHeader,
                                broker: ActorRefT[ChainHandler.Event],
                                origin: DataOrigin): Unit = {
    flowHandler ! FlowHandler.AddHeader(header, broker, origin)
  }

  override def pendingToFlowHandler(header: BlockHeader,
                                    missings: mutable.HashSet[Hash],
                                    broker: ActorRefT[ChainHandler.Event],
                                    origin: DataOrigin,
                                    self: ActorRefT[Command]): Unit = {
    flowHandler ! FlowHandler.PendingHeader(header, missings, origin, broker, self)
  }

  override def dataAddedEvent(): Event = HeadersAdded(chainIndex)

  override def dataAddingFailed(): Event = HeadersAddingFailed

  override def dataInvalid(): Event = InvalidHeaders
}
