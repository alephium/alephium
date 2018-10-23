package org.alephium.flow.client

import akka.actor.{ActorRef, ActorSystem}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.{PeerManager, TcpHandler, TcpServer}
import org.alephium.flow.storage.{BlockFlow, BlockHandlers, ChainHandler, FlowHandler}
import org.alephium.protocol.model.{ChainIndex, PeerId}
import org.alephium.util.AVector

case class Node(
    name: String,
    port: Int,
    chainIndex: ChainIndex,
    peerId: PeerId,
    system: ActorSystem,
    blockFlow: BlockFlow,
    peerManager: ActorRef,
    blockHandlers: BlockHandlers
)

object Node {
  type Builder = TcpHandler.Builder

  def apply(builders: Builder, name: String, chainIndex: ChainIndex, port: Int, groups: Int)(
      implicit config: PlatformConfig): Node = {
    val peerId = PeerId.generateFor(chainIndex)

    val system    = ActorSystem(name, config.all)
    val blockFlow = BlockFlow()

    val peerManager  = system.actorOf(PeerManager.props(builders), "PeerManager")
    val blockHandler = system.actorOf(FlowHandler.props(blockFlow), "BlockHandler")
    val chainHandlers = AVector.tabulate(groups, groups) {
      case (from, to) =>
        system.actorOf(ChainHandler.props(blockFlow, ChainIndex(from, to), peerManager),
                       s"ChainHandler-$from-$to")
    }
    val blockHandlers = BlockHandlers(blockHandler, chainHandlers)
    val server        = system.actorOf(TcpServer.props(port, peerManager), "TcpServer")
    peerManager ! PeerManager.Set(server, blockHandlers)

    Node(name, port, chainIndex, peerId, system, blockFlow, peerManager, blockHandlers)
  }
}
