package org.alephium.flow.client

import akka.actor.{ActorRef, ActorSystem, Props}

import org.alephium.flow.core._
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpServer}
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.platform.PlatformConfig
import org.alephium.util.EventBus

final case class Node(builders: BrokerHandler.Builder, name: String)(
    implicit config: PlatformConfig) {
  val system: ActorSystem = ActorSystem(name, config.all)

  val blockFlow: BlockFlow = BlockFlow.createUnsafe()

  val server: ActorRef = system.actorOf(TcpServer.props(config.publicAddress.getPort), "TcpServer")

  val eventBus: ActorRef = system.actorOf(EventBus.props())

  val discoveryProps: Props     = DiscoveryServer.props(config.bootstrap)(config)
  val discoveryServer: ActorRef = system.actorOf(discoveryProps, "DiscoveryServer")
  val cliqueManager: ActorRef =
    system.actorOf(CliqueManager.props(builders, discoveryServer), "CliqueManager")

  val allHandlers: AllHandlers = AllHandlers.build(system, cliqueManager, blockFlow, eventBus)

  cliqueManager ! allHandlers

  val boostraper: ActorRef =
    system.actorOf(Bootstrapper.props(server, discoveryServer, cliqueManager), "Bootstrapper")
}
