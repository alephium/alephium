package org.alephium.client

import akka.actor.{ActorRef, ActorSystem}
import org.alephium.network.PeerManager
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.storage.{BlockFlow, BlockHandler, BlockHandlers, BlockPoolHandler}

case class Node(
    name: String,
    port: Int,
    system: ActorSystem,
    peerManager: ActorRef,
    blockHandlers: BlockHandlers
)

object Node {
  def apply(name: String, port: Int, groups: Int): Node = {
    val system      = ActorSystem(name)
    val blockFlow   = BlockFlow()
    val peerManager = system.actorOf(PeerManager.props(port), "PeerManager")

    val blockHandler = system.actorOf(BlockHandler.props(blockFlow), "BlockHandler")
    val poolHandlers = Seq.tabulate(groups, groups) {
      case (from, to) =>
        system.actorOf(BlockPoolHandler.props(blockFlow, ChainIndex(from, to), peerManager))
    }
    val blockHandlers = BlockHandlers(blockHandler, poolHandlers)
    peerManager ! PeerManager.SetBlockHandlers(blockHandlers)

    Node(name, port, system, peerManager, blockHandlers)
  }
}
