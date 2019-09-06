package org.alephium.flow.client

import akka.actor.ActorSystem

import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpServer}
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.storage._
import org.alephium.util.EventBus

case class Node(builders: BrokerHandler.Builder, name: String)(implicit config: PlatformConfig) {
  val system = ActorSystem(name, config.all)

  val blockFlow = BlockFlow.createUnsafe()

  val server = system.actorOf(TcpServer.props(config.publicAddress.getPort), "TcpServer")

  val eventBus = system.actorOf(EventBus.props())

  val discoveryProps  = DiscoveryServer.props(config.bootstrap)(config)
  val discoveryServer = system.actorOf(discoveryProps, "DiscoveryServer")
  val cliqueManager =
    system.actorOf(CliqueManager.props(builders, discoveryServer), "CliqueManager")

  val allHandlers = AllHandlers.build(system, cliqueManager, blockFlow)
  cliqueManager ! allHandlers

  val boostraper =
    system.actorOf(Bootstrapper.props(server, discoveryServer, cliqueManager), "Bootstrapper")
}
