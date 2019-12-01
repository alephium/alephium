package org.alephium.flow.core

import scala.collection.mutable

import akka.actor.{ActorRef, Props}

import org.alephium.crypto.Keccak256
import org.alephium.flow.core.validation._
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.{BlockHeader, ChainIndex}
import org.alephium.util.Forest

object HeaderChainHandler {
  def props(blockFlow: BlockFlow, chainIndex: ChainIndex, flowHandler: ActorRef)(
      implicit config: PlatformProfile): Props =
    Props(new HeaderChainHandler(blockFlow, chainIndex, flowHandler))

  sealed trait Command
  case class AddHeaders(header: Forest[Keccak256, BlockHeader], origin: DataOrigin) extends Command
  case class AddPendingHeader(header: BlockHeader, origin: DataOrigin)              extends Command

  sealed trait Event                              extends ChainHandler.Event
  case class HeadersAdded(chainIndex: ChainIndex) extends Event
  case object HeadersAddingFailed                 extends Event
  case object InvalidHeaders                      extends Event
}

class HeaderChainHandler(blockFlow: BlockFlow, chainIndex: ChainIndex, flowHandler: ActorRef)(
    implicit val config: PlatformProfile)
    extends ChainHandler[BlockHeader, HeaderStatus](blockFlow, chainIndex, HeaderValidation) {
  import HeaderChainHandler._

  override def receive: Receive = {
    case AddHeaders(headers, origin)      => handleDatas(headers, origin)
    case AddPendingHeader(header, origin) => handlePending(header, origin)
  }

  override def broadcast(header: BlockHeader, origin: DataOrigin): Unit = ()

  override def addToFlowHandler(header: BlockHeader, origin: DataOrigin, sender: ActorRef): Unit = {
    flowHandler.tell(FlowHandler.AddHeader(header), sender)
  }

  override def pendingToFlowHandler(header: BlockHeader,
                                    missings: mutable.HashSet[Keccak256],
                                    origin: DataOrigin,
                                    sender: ActorRef,
                                    self: ActorRef): Unit = {
    flowHandler ! FlowHandler.PendingHeader(header, missings, origin, sender, self)
  }

  override def dataAddedEvent(): Event = HeadersAdded(chainIndex)

  override def dataAddingFailed(): Event = HeadersAddingFailed

  override def dataInvalid(): Event = InvalidHeaders
}
