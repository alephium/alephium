package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp

import org.alephium.flow.PlatformProfile
import org.alephium.flow.network.bootstrap.{Broker, CliqueCoordinator}
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.BaseActor

object Bootstrapper {
  def props(server: ActorRef, discoveryServer: ActorRef, cliqueManager: ActorRef)(
      implicit config: PlatformProfile): Props =
    Props(new Bootstrapper(server, discoveryServer, cliqueManager))

  sealed trait Command
  case object ForwardConnection extends Command
}

// TODO: close this properly
class Bootstrapper(server: ActorRef, discoveryServer: ActorRef, cliqueManager: ActorRef)(
    implicit config: PlatformProfile)
    extends BaseActor {
  import Bootstrapper._

  server ! TcpServer.Start(self)

  val sink = if (config.isCoordinator) {
    log.debug("Start as CliqueCoordinator")
    context.actorOf(CliqueCoordinator.props(), "CliqueCoordinator")
  } else {
    log.debug("Start as Broker")
    context.actorOf(Broker.props(), "Broker")
  }

  override def receive: Receive =
    if (config.isCoordinator) prepareForCoordinator else awaitInfo

  def prepareForBroker: Receive = {
    case c: Tcp.Connected =>
      if (c.remoteAddress == config.masterAddress) {
        sink.forward(c)
        server ! cliqueManager
        context become awaitInfo
      }
  }

  def prepareForCoordinator: Receive = {
    case c: Tcp.Connected =>
      sink.forward(c)
    case ForwardConnection =>
      server ! cliqueManager
      context become awaitInfo
  }

  def awaitInfo: Receive = {
    case cliqueInfo: CliqueInfo =>
      cliqueManager ! CliqueManager.Start(cliqueInfo)
      discoveryServer ! cliqueInfo
    case c: Tcp.Connected =>
      log.debug(s"Forward connection to clique manager")
      cliqueManager.forward(c)
  }
}
