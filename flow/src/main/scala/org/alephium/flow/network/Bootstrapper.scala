package org.alephium.flow.network

import akka.actor.{ActorContext, ActorRef, Props}
import akka.io.Tcp

import org.alephium.flow.network.bootstrap.{Broker, CliqueCoordinator, IntraCliqueInfo, PeerInfo}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig}
import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.util.{ActorRefT, AVector, BaseActor}

object Bootstrapper {
  def props(
      tcpController: ActorRefT[TcpController.Command],
      discoveryServer: ActorRefT[DiscoveryServer.Command],
      cliqueManager: ActorRefT[CliqueManager.Command]
  )(implicit brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting,
    discoveryConfig: DiscoveryConfig): Props =
    props(
      tcpController,
      discoveryServer,
      cliqueManager,
      Builder.cliqueCoordinator,
      Builder.broker
    )

  def props(
      tcpController: ActorRefT[TcpController.Command],
      discoveryServer: ActorRefT[DiscoveryServer.Command],
      cliqueManager: ActorRefT[CliqueManager.Command],
      cliqueCoordinatorBuilder: (ActorContext, ActorRef) => ActorRef,
      brokerBuilder: (ActorContext, ActorRef)            => ActorRef
  )(implicit brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting,
    discoveryConfig: DiscoveryConfig): Props = {
    if (brokerConfig.brokerNum == 1) {
      assume(brokerConfig.groupNumPerBroker == brokerConfig.groups)
      val cliqueId  = CliqueId.unsafe(discoveryConfig.discoveryPublicKey.bytes)
      val peerInfos = AVector(PeerInfo.self)
      val intraCliqueInfo =
        IntraCliqueInfo.unsafe(cliqueId, peerInfos, brokerConfig.groupNumPerBroker)
      Props(
        new SingleNodeCliqueBootstrapper(tcpController,
                                         discoveryServer,
                                         cliqueManager,
                                         intraCliqueInfo))
    } else if (networkSetting.isCoordinator) {
      Props(
        new CliqueCoordinatorBootstrapper(tcpController,
                                          discoveryServer,
                                          cliqueManager,
                                          cliqueCoordinatorBuilder))
    } else {
      Props(new BrokerBootstrapper(tcpController, discoveryServer, cliqueManager, brokerBuilder))
    }
  }

  object Builder {
    def cliqueCoordinator(
        implicit brokerConfig: BrokerConfig,
        networkSetting: NetworkSetting,
        discoveryConfig: DiscoveryConfig): (ActorContext, ActorRef) => ActorRef = {
      (actorContext, bootstrapper) =>
        actorContext.actorOf(
          CliqueCoordinator.props(ActorRefT[Bootstrapper.Command](bootstrapper)),
          "CliqueCoordinator"
        )
    }
    def broker(implicit brokerConfig: BrokerConfig,
               networkSetting: NetworkSetting): (ActorContext, ActorRef) => ActorRef = {
      (actorContext, bootstrapper) =>
        actorContext.actorOf(
          Broker.props(ActorRefT[Bootstrapper.Command](bootstrapper)),
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

class CliqueCoordinatorBootstrapper(val tcpController: ActorRefT[TcpController.Command],
                                    val discoveryServer: ActorRefT[DiscoveryServer.Command],
                                    val cliqueManager: ActorRefT[CliqueManager.Command],
                                    cliqueCoordinatorBuilder: (ActorContext, ActorRef) => ActorRef)
    extends BootstrapperHandler {

  log.debug("Start as CliqueCoordinator")

  val cliqueCoordinator: ActorRef = cliqueCoordinatorBuilder(context, self)

  override def receive: Receive = {
    case c: Tcp.Connected =>
      log.debug(s"Connected to ${c.remoteAddress}")
      cliqueCoordinator.forward(c)
    case Bootstrapper.ForwardConnection =>
      tcpController ! TcpController.WorkFor(cliqueManager.ref)
      context become (awaitInfo orElse forwardConnection)
  }
}

class BrokerBootstrapper(val tcpController: ActorRefT[TcpController.Command],
                         val discoveryServer: ActorRefT[DiscoveryServer.Command],
                         val cliqueManager: ActorRefT[CliqueManager.Command],
                         brokerBuilder: (ActorContext, ActorRef) => ActorRef)
    extends BootstrapperHandler {
  log.debug("Start as Broker")
  brokerBuilder(context, self)

  override def receive: Receive = awaitInfo
}

class SingleNodeCliqueBootstrapper(val tcpController: ActorRefT[TcpController.Command],
                                   val discoveryServer: ActorRefT[DiscoveryServer.Command],
                                   val cliqueManager: ActorRefT[CliqueManager.Command],
                                   intraCliqueInfo: IntraCliqueInfo)
    extends BootstrapperHandler {
  log.debug("Start as single node clique bootstrapper")
  self ! Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo)

  override def receive: Receive = awaitInfo
}

// TODO: close this properly
trait BootstrapperHandler extends BaseActor {
  tcpController ! TcpController.Start(self)

  val tcpController: ActorRefT[TcpController.Command]
  val discoveryServer: ActorRefT[DiscoveryServer.Command]
  val cliqueManager: ActorRefT[CliqueManager.Command]

  def awaitInfo: Receive = {
    case Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo) =>
      val cliqueInfo = intraCliqueInfo.cliqueInfo
      tcpController ! TcpController.WorkFor(cliqueManager.ref)
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
      cliqueManager.ref.forward(c) // cliqueManager receives connection from TcpServer too
  }
}
