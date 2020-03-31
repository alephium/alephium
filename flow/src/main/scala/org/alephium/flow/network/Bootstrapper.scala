package org.alephium.flow.network

import akka.actor.{ActorContext, ActorRef, Props}
import akka.io.Tcp

import org.alephium.flow.network.bootstrap.{Broker, CliqueCoordinator, IntraCliqueInfo}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.{ActorRefT, BaseActor}

object Bootstrapper {
  def props(
      server: ActorRefT[TcpServer.Command],
      discoveryServer: ActorRefT[DiscoveryServer.Command],
      cliqueManager: ActorRefT[CliqueManager.Command]
  )(implicit config: PlatformConfig): Props =
    props(
      server,
      discoveryServer,
      cliqueManager,
      Builder.cliqueCoordinator,
      Builder.broker
    )

  def props(
      server: ActorRefT[TcpServer.Command],
      discoveryServer: ActorRefT[DiscoveryServer.Command],
      cliqueManager: ActorRefT[CliqueManager.Command],
      cliqueCoordinatorBuilder: (ActorContext, ActorRef) => ActorRef,
      brokerBuilder: (ActorContext, ActorRef)            => ActorRef
  )(implicit config: PlatformConfig): Props = {
    if (config.isCoordinator) {
      Props(
        new CliqueCoordinatorBootstrapper(server,
                                          discoveryServer,
                                          cliqueManager,
                                          cliqueCoordinatorBuilder))
    } else {
      Props(new BrokerBootstrapper(server, discoveryServer, cliqueManager, brokerBuilder))
    }
  }

  object Builder {
    def cliqueCoordinator(implicit config: PlatformConfig): (ActorContext, ActorRef) => ActorRef = {
      (actorContext, bootstrapper) =>
        actorContext.actorOf(
          CliqueCoordinator.props(ActorRefT[Bootstrapper.Command](bootstrapper)),
          "CliqueCoordinator"
        )
    }
    def broker(implicit config: PlatformConfig): (ActorContext, ActorRef) => ActorRef = {
      (actorContext, bootstrapper) =>
        actorContext.actorOf(
          Broker.props(config.masterAddress,
                       config.brokerInfo,
                       config.retryTimeout,
                       ActorRefT[Bootstrapper.Command](bootstrapper)),
          "Broker"
        )
    }
  }

  sealed trait Command
  case object ForwardConnection                                          extends Command
  case object GetIntraCliqueInfo                                         extends Command
  final case class SendCliqueInfo(cliqueInfo: CliqueInfo)                extends Command
  final case class SendIntraCliqueInfo(intraCliqueInfo: IntraCliqueInfo) extends Command
}

class CliqueCoordinatorBootstrapper(val server: ActorRefT[TcpServer.Command],
                                    val discoveryServer: ActorRefT[DiscoveryServer.Command],
                                    val cliqueManager: ActorRefT[CliqueManager.Command],
                                    cliqueCoordinatorBuilder: (ActorContext, ActorRef) => ActorRef)
    extends BootstrapperHandler {

  log.debug("Start as CliqueCoordinator")

  val cliqueCoordinator: ActorRef = cliqueCoordinatorBuilder(context, self)

  override def receive: Receive = {
    case c: Tcp.Connected =>
      cliqueCoordinator.forward(c)
    case Bootstrapper.ForwardConnection =>
      server ! TcpServer.WorkFor(cliqueManager.ref)
      context become (awaitInfo orElse forwardConnection)
  }

}

class BrokerBootstrapper(val server: ActorRefT[TcpServer.Command],
                         val discoveryServer: ActorRefT[DiscoveryServer.Command],
                         val cliqueManager: ActorRefT[CliqueManager.Command],
                         brokerBuilder: (ActorContext, ActorRef) => ActorRef)
    extends BootstrapperHandler {
  log.debug("Start as Broker")
  brokerBuilder(context, self)
  override def receive: Receive = awaitInfo
}

// TODO: close this properly
trait BootstrapperHandler extends BaseActor {
  server ! TcpServer.Start(self)

  val server: ActorRefT[TcpServer.Command]
  val discoveryServer: ActorRefT[DiscoveryServer.Command]
  val cliqueManager: ActorRefT[CliqueManager.Command]
  def awaitInfo: Receive = {
    case Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo) =>
      val cliqueInfo = intraCliqueInfo.cliqueInfo
      cliqueManager ! CliqueManager.Start(cliqueInfo)
      discoveryServer ! DiscoveryServer.SendCliqueInfo(cliqueInfo)

      context become (ready(intraCliqueInfo) orElse forwardConnection)
  }

  def ready(cliqueInfo: IntraCliqueInfo): Receive = {
    case Bootstrapper.GetIntraCliqueInfo => sender() ! cliqueInfo
  }

  def forwardConnection: Receive = {
    case c: Tcp.Connected =>
      log.debug(s"Forward connection to clique manager")
      cliqueManager.forward(CliqueManager.SendTcpConnected(c))
  }
}
