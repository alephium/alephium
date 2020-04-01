package org.alephium.flow.core

import scala.collection.mutable

import akka.actor.Props

import org.alephium.flow.core.FlowHandler.HeaderAdded
import org.alephium.flow.core.validation._
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{BlockHeader, ChainIndex}
import org.alephium.util.{ActorRefT, Forest}

object HeaderChainHandler {
  def props(blockFlow: BlockFlow,
            chainIndex: ChainIndex,
            flowHandler: ActorRefT[FlowHandler.Command])(implicit config: PlatformConfig): Props =
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

class HeaderChainHandler(
    blockFlow: BlockFlow,
    chainIndex: ChainIndex,
    flowHandler: ActorRefT[FlowHandler.Command])(implicit val config: PlatformConfig)
    extends ChainHandler[BlockHeader, HeaderStatus, HeaderChainHandler.Command](blockFlow,
                                                                                chainIndex,
                                                                                HeaderValidation) {
  import HeaderChainHandler._

  override def receive: Receive = {
    case AddHeaders(headers, origin) =>
      handleDatas(headers, ActorRefT[ChainHandler.Event](sender()), origin)
    case AddPendingHeader(header, broker, origin) => handlePending(header, broker, origin)
    case HeaderAdded(header, broker, origin)      => handleDataAdded(header, broker, origin)
  }

  @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
  override def handleMissingParent(headers: Forest[Hash, BlockHeader],
                                   broker: ActorRefT[ChainHandler.Event],
                                   origin: DataOrigin): Unit = {
    assert(origin.isInstanceOf[DataOrigin.IntraClique])
    log.warning(s"missing parent headers, might be bug or compromised node in the clique")
    feedbackAndClear(broker, dataInvalid())
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
