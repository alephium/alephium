package org.alephium.client

import akka.actor.{ActorRef, ActorSystem}
import org.alephium.network.PeerManager
import org.alephium.storage.BlockHandler

case class Node(
    name: String,
    port: Int,
    system: ActorSystem,
    blockHandler: ActorRef,
    peerManager: ActorRef
)

object Node {
  def apply(name: String, port: Int): Node = {
    val system       = ActorSystem(name)
    val blockHandler = system.actorOf(BlockHandler.props())
    val peerManager  = system.actorOf(PeerManager.props(port, blockHandler))
    Node(name, port, system, blockHandler, peerManager)
  }
}
