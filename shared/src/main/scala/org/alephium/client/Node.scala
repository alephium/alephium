package org.alephium.client

import akka.actor.{ActorRef, ActorSystem, Props}
import org.alephium.network.{PeerManager, TcpHandler, MessageHandler}
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.storage.{BlockFlow, BlockHandlers, ChainHandler, FlowHandler}

case class Node(
    name: String,
    port: Int,
    system: ActorSystem,
    peerManager: ActorRef,
    blockHandlers: BlockHandlers
)

object Node {
  def apply(builders: TcpHandler.Builder with MessageHandler.Builder,
            name: String, port: Int, groups: Int): Node = {

    val system      = ActorSystem(name)
    val blockFlow   = BlockFlow()
    val peerManager = system.actorOf(Props(new PeerManager(builders, port)), "PeerManager")

    val blockHandler = system.actorOf(FlowHandler.props(blockFlow), "BlockHandler")
    val chainHandlers = Seq.tabulate(groups, groups) {
      case (from, to) =>
        system.actorOf(ChainHandler.props(blockFlow, ChainIndex(from, to), peerManager))
    }
    val blockHandlers = BlockHandlers(blockHandler, chainHandlers)
    peerManager ! PeerManager.SetBlockHandlers(blockHandlers)

    Node(name, port, system, peerManager, blockHandlers)
  }
}
