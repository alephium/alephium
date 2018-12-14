package org.alephium.flow.client

import akka.actor.{ActorRef, ActorSystem}
import org.alephium.flow.model.ChainIndex
import org.alephium.flow.network.{PeerManager, TcpHandler, TcpServer}
import org.alephium.flow.storage.{BlockFlow, BlockHandlers, ChainHandler, FlowHandler}

case class Node(
    name: String,
    port: Int,
    system: ActorSystem,
    blockFlow: BlockFlow,
    peerManager: ActorRef,
    blockHandlers: BlockHandlers
)

object Node {
  type Builder = TcpHandler.Builder

  def apply(builders: Builder, name: String, port: Int, groups: Int): Node = {

    val system    = ActorSystem(name)
    val blockFlow = BlockFlow()

    val peerManager  = system.actorOf(PeerManager.props(builders), "PeerManager")
    val blockHandler = system.actorOf(FlowHandler.props(blockFlow), "BlockHandler")
    val chainHandlers = Seq.tabulate(groups, groups) {
      case (from, to) =>
        system.actorOf(ChainHandler.props(blockFlow, ChainIndex(from, to), peerManager),
                       s"ChainHandler-$from-$to")
    }
    val blockHandlers = BlockHandlers(blockHandler, chainHandlers)
    val server        = system.actorOf(TcpServer.props(port, peerManager), "TcpServer")
    peerManager ! PeerManager.Set(server, blockHandlers)

    Node(name, port, system, blockFlow, peerManager, blockHandlers)
  }
}
