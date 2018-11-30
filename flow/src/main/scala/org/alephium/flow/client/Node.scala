package org.alephium.flow.client

import akka.actor.{ActorRef, ActorSystem}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.{DiscoveryServer, PeerManager, TcpHandler, TcpServer}
import org.alephium.flow.storage._

case class Node(
    name: String,
    config: PlatformConfig,
    system: ActorSystem,
    blockFlow: BlockFlow,
    peerManager: ActorRef,
    allHandlers: AllHandlers
)

object Node {
  type Builder = TcpHandler.Builder

  def createUnsafe(builders: Builder, name: String)(implicit config: PlatformConfig): Node = {

    val system      = ActorSystem(name, config.all)
    val peerManager = system.actorOf(PeerManager.props(builders), "PeerManager")

    val blockFlow       = BlockFlow.createUnsafe()
    val allHandlers     = AllHandlers.build(system, peerManager, blockFlow)
    val server          = system.actorOf(TcpServer.props(config.port, peerManager), "TcpServer")
    val discoveryProps  = DiscoveryServer.props(config.bootstrap)(config.discoveryConfig)
    val discoveryServer = system.actorOf(discoveryProps, "DiscoveryServer")
    peerManager ! PeerManager.Set(server, allHandlers, discoveryServer)

    new Node(name, config, system, blockFlow, peerManager, allHandlers)
  }
}
