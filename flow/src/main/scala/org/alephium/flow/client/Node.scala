package org.alephium.flow.client

import akka.actor.{ActorSystem, Props}

import org.alephium.flow.core._
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpServer}
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.platform.PlatformConfig
import org.alephium.util.{ActorRefT, EventBus}

trait Node {
  implicit def config: PlatformConfig
  def system: ActorSystem
  def blockFlow: BlockFlow
  def allHandlers: AllHandlers
  def server: ActorRefT[TcpServer.Command]
  def eventBus: ActorRefT[EventBus.Message]
  def discoveryServer: ActorRefT[DiscoveryServer.Command]
  def cliqueManager: ActorRefT[CliqueManager.Command]
  def boostraper: ActorRefT[Bootstrapper.Command]
}

object Node {
  def build(builders: BrokerHandler.Builder, name: String)(
      implicit platformConfig: PlatformConfig): Node = new Node {
    val config              = platformConfig
    val system: ActorSystem = ActorSystem(name, config.all)

    val blockFlow: BlockFlow = BlockFlow.createUnsafe()

    val server: ActorRefT[TcpServer.Command] = ActorRefT
      .build[TcpServer.Command](system, TcpServer.props(config.publicAddress.getPort), "TcpServer")

    val eventBus: ActorRefT[EventBus.Message] =
      ActorRefT.build[EventBus.Message](system, EventBus.props(), "EventBus")

    val discoveryProps: Props = DiscoveryServer.props(config.bootstrap)(config)
    val discoveryServer: ActorRefT[DiscoveryServer.Command] =
      ActorRefT.build[DiscoveryServer.Command](system, discoveryProps, "DiscoveryServer")
    val cliqueManager: ActorRefT[CliqueManager.Command] =
      ActorRefT.build(system, CliqueManager.props(builders, discoveryServer), "CliqueManager")

    val allHandlers: AllHandlers = AllHandlers.build(system, cliqueManager, blockFlow, eventBus)

    cliqueManager ! CliqueManager.SendAllHandlers(allHandlers)

    val boostraper: ActorRefT[Bootstrapper.Command] =
      ActorRefT.build[Bootstrapper.Command](
        system,
        Bootstrapper.props(server, discoveryServer, cliqueManager),
        "Bootstrapper")
  }
}
