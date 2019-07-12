package org.alephium.flow.storage

import akka.actor.{ActorRef, Props}
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{BlockHeader, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object HeaderChainHandler {
  def props(blockFlow: BlockFlow, chainIndex: ChainIndex, flowHandler: ActorRef)(
      implicit config: PlatformConfig): Props =
    Props(new HeaderChainHandler(blockFlow, chainIndex, flowHandler))

  sealed trait Command
  case class AddHeaders(headers: AVector[BlockHeader])
}

class HeaderChainHandler(val blockFlow: BlockFlow,
                         val chainIndex: ChainIndex,
                         flowHandler: ActorRef)(implicit val config: PlatformConfig)
    extends BaseActor
    with ChainHandlerLogger {
  import HeaderChainHandler._

  val chain: BlockHeaderPool = blockFlow.getHeaderChain(chainIndex)

  override def receive: Receive = {
    case AddHeaders(headers) =>
      // TODO: support more heads later
      assert(headers.length == 1)
      val header = headers.head
      handleHeader(header)
  }

  def handleHeader(header: BlockHeader): Unit = {
    if (blockFlow.contains(header)) {
      log.debug(s"Header for ${header.chainIndex} already existed")
    } else {
      blockFlow.validate(header, fromBlock = false) match {
        case Left(e) =>
          log.debug(s"Failed in header validation: ${e.toString}")
        case Right(_) =>
          logInfo(header)
          flowHandler.tell(FlowHandler.AddHeader(header), sender())
      }
    }
  }
}
