package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.coordinator.{Broker, CliqueCoordinator}
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.BaseActor

object Bootstrapper {
  def props(server: ActorRef, discoveryServer: ActorRef, cliqueManager: ActorRef)(
      implicit config: PlatformConfig): Props =
    Props(new Bootstrapper(server, discoveryServer, cliqueManager))
}

class Bootstrapper(server: ActorRef, discoveryServer: ActorRef, cliqueManager: ActorRef)(
    implicit config: PlatformConfig)
    extends BaseActor {
  server ! TcpServer.Start(self)

  val sink = if (config.isMaster) {
    log.debug("Start as CliqueCoordinator")
    context.actorOf(CliqueCoordinator.props(), "CliqueCoordinator")
  } else {
    log.debug("Start as Broker")
    context.actorOf(Broker.props(), "Broker")
  }

  override def receive: Receive = awaitCliqueInfo

  def awaitCliqueInfo: Receive = {
    case cliqueInfo: CliqueInfo =>
      cliqueManager ! CliqueManager.Start(cliqueInfo)
      server ! cliqueManager
      discoveryServer ! cliqueInfo
      context stop self
    case c: Tcp.Connected =>
      sink.forward(c)
  }
}
