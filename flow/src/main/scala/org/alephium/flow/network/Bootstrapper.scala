package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp

import org.alephium.flow.network.bootstrap.{Broker, CliqueCoordinator, IntraCliqueInfo}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.{ActorRefT, BaseActor}

object Bootstrapper {
  def props(
      server: ActorRefT[TcpServer.Command],
      discoveryServer: ActorRefT[DiscoveryServer.Command],
      cliqueManager: ActorRefT[CliqueManager.Command])(implicit config: PlatformConfig): Props =
    Props(new Bootstrapper(server, discoveryServer, cliqueManager))

  sealed trait Command
  case object ForwardConnection                                          extends Command
  case object GetIntraCliqueInfo                                         extends Command
  final case class SendCliqueInfo(cliqueInfo: CliqueInfo)                extends Command
  final case class SendIntraCliqueInfo(intraCliqueInfo: IntraCliqueInfo) extends Command
}

// TODO: close this properly
class Bootstrapper(server: ActorRefT[TcpServer.Command],
                   discoveryServer: ActorRefT[DiscoveryServer.Command],
                   cliqueManager: ActorRefT[CliqueManager.Command])(implicit config: PlatformConfig)
    extends BaseActor {
  import Bootstrapper._

  override def preStart(): Unit = server ! TcpServer.Start(self)

  val sink: ActorRef = if (config.isCoordinator) {
    log.debug("Start as CliqueCoordinator")
    context.actorOf(CliqueCoordinator.props(ActorRefT(self)), "CliqueCoordinator")
  } else {
    log.debug("Start as Broker")
    context.actorOf(
      Broker.props(config.masterAddress,
                   config.brokerInfo,
                   config.retryTimeout,
                   ActorRefT[Bootstrapper.Command](self)),
      "Broker"
    )
  }

  override def receive: Receive =
    if (config.isCoordinator) prepareForCoordinator else awaitInfo

  def prepareForCoordinator: Receive = {
    case c: Tcp.Connected =>
      sink.forward(c)
    case ForwardConnection =>
      server ! TcpServer.WorkFor(cliqueManager.ref)
      context become (awaitInfo orElse forwardConnection)
  }

  def awaitInfo: Receive = {
    case SendIntraCliqueInfo(intraCliqueInfo) =>
      val cliqueInfo = intraCliqueInfo.cliqueInfo
      cliqueManager ! CliqueManager.Start(cliqueInfo)
      discoveryServer ! DiscoveryServer.SendCliqueInfo(cliqueInfo)

      context become (ready(intraCliqueInfo) orElse forwardConnection)
  }

  def ready(cliqueInfo: IntraCliqueInfo): Receive = {
    case GetIntraCliqueInfo => sender() ! cliqueInfo
  }

  def forwardConnection: Receive = {
    case c: Tcp.Connected =>
      log.debug(s"Forward connection to clique manager")
      cliqueManager.forward(CliqueManager.SendTcpConnected(c))
  }
}
