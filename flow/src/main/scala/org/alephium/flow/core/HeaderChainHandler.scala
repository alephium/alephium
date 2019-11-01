package org.alephium.flow.core

import akka.actor.{ActorRef, Props}

import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.message.{Message, SendHeaders}
import org.alephium.protocol.model.{BlockHeader, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object HeaderChainHandler {
  def props(blockFlow: BlockFlow,
            chainIndex: ChainIndex,
            cliqueManager: ActorRef,
            flowHandler: ActorRef)(implicit config: PlatformProfile): Props =
    Props(new HeaderChainHandler(blockFlow, chainIndex, cliqueManager, flowHandler))

  sealed trait Command
  case class AddHeader(header: BlockHeader, origin: DataOrigin.Remote)
}

class HeaderChainHandler(val blockFlow: BlockFlow,
                         val chainIndex: ChainIndex,
                         cliqueManager: ActorRef,
                         flowHandler: ActorRef)(implicit val config: PlatformProfile)
    extends BaseActor
    with ChainHandlerLogger {
  import HeaderChainHandler._

  val chain: BlockHeaderPool = blockFlow.getHeaderChain(chainIndex)

  override def receive: Receive = {
    case AddHeader(header, origin) =>
      handleHeader(header, origin)
  }

  def handleHeader(header: BlockHeader, origin: DataOrigin.Remote): Unit = {
    if (blockFlow.contains(header)) {
      log.debug(s"Header for ${header.chainIndex} already existed")
    } else {
      blockFlow.validate(header, origin.isSyncing) match {
        case Left(e) =>
          log.debug(s"IO failed in header validation: ${e.toString}")
        case Right(x: InvalidHeaderStatus) =>
          log.debug(s"IO failed in header validation: $x")
        case Right(_: ValidHeader.type) =>
          logInfo(header)
          broadcast(header, origin)
          flowHandler.tell(FlowHandler.AddHeader(header), sender())
      }
    }
  }

  def broadcast(header: BlockHeader, origin: DataOrigin): Unit = {
    val headerMessage = Message.serialize(SendHeaders(AVector(header)))
    cliqueManager ! CliqueManager.BroadCastHeader(header, headerMessage, origin)
  }
}
