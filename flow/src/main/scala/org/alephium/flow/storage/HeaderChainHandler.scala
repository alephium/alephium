package org.alephium.flow.storage

import akka.actor.{ActorRef, Props}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{PeerManager, TcpHandler}
import org.alephium.protocol.message.SendHeaders
import org.alephium.protocol.model.{BlockHeader, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object HeaderChainHandler {
  def props(blockFlow: BlockFlow, chainIndex: ChainIndex, peerManager: ActorRef)(
      implicit config: PlatformConfig): Props =
    Props(new HeaderChainHandler(blockFlow, chainIndex, peerManager))

  sealed trait Command
  case class AddHeaders(headers: AVector[BlockHeader], origin: DataOrigin)
}

class HeaderChainHandler(blockFlow: BlockFlow, chainIndex: ChainIndex, peerManager: ActorRef)(
    implicit config: PlatformConfig)
    extends BaseActor {
  import HeaderChainHandler._

  val chain = blockFlow.getHeaderChain(chainIndex)

  override def receive: Receive = {
    case AddHeaders(headers, origin) =>
      // TODO: support more heads later
      assert(headers.length == 1)
      val header = headers.head
      val result = blockFlow.add(header)
      result match {
        case AddBlockHeaderResult.Success =>
          logInfo(header)
          broadcast(header, origin)
        case AddBlockHeaderResult.AlreadyExisted =>
          log.debug(s"Header already existed")
        case x: AddBlockHeaderResult.Incomplete =>
          // TODO: handle missing data
          log.debug(s"No enough data to verify header: ${x.toString}")
        case x: AddBlockHeaderResult.Error =>
          log.warning(s"Failed in adding new header: ${x.toString}")
      }
      sender() ! result
  }

  def logInfo(header: BlockHeader): Unit = {
    val total       = blockFlow.numHashes - config.chainNum // exclude genesis blocks
    val elapsedTime = System.currentTimeMillis() - header.timestamp
    log.info(
      s"Index: $chainIndex; Total: $total; ${chain.show(header.hash)}; Time elapsed: ${elapsedTime}ms")
  }

  def broadcast(header: BlockHeader, origin: DataOrigin): Unit = {
    val headerMsg = TcpHandler.envelope(SendHeaders(AVector(header)))
    peerManager ! PeerManager.BroadCastHeader(header, headerMsg, origin)
  }
}
