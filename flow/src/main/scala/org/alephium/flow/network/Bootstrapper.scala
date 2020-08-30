package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp

import org.alephium.flow.network.bootstrap.{Broker, CliqueCoordinator, IntraCliqueInfo, PeerInfo}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig}
import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.util.{ActorRefT, AVector, BaseActor}

object Bootstrapper {
  def props(
      tcpController: ActorRefT[TcpController.Command],
      cliqueManager: ActorRefT[CliqueManager.Command]
  )(implicit brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting,
    discoveryConfig: DiscoveryConfig): Props = {
    if (brokerConfig.brokerNum == 1) {
      assume(brokerConfig.groupNumPerBroker == brokerConfig.groups)
      val cliqueId  = CliqueId.unsafe(discoveryConfig.discoveryPublicKey.bytes)
      val peerInfos = AVector(PeerInfo.self)
      val intraCliqueInfo =
        IntraCliqueInfo.unsafe(cliqueId, peerInfos, brokerConfig.groupNumPerBroker)
      Props(new SingleNodeCliqueBootstrapper(tcpController, cliqueManager, intraCliqueInfo))
    } else if (networkSetting.isCoordinator) {
      Props(new CliqueCoordinatorBootstrapper(tcpController, cliqueManager))
    } else {
      Props(new BrokerBootstrapper(tcpController, cliqueManager))
    }
  }

  sealed trait Command
  case object ForwardConnection                                          extends Command
  case object GetIntraCliqueInfo                                         extends Command
  final case class SendCliqueInfo(cliqueInfo: CliqueInfo)                extends Command
  final case class SendIntraCliqueInfo(intraCliqueInfo: IntraCliqueInfo) extends Command
}

class CliqueCoordinatorBootstrapper(
    val tcpController: ActorRefT[TcpController.Command],
    val cliqueManager: ActorRefT[CliqueManager.Command]
)(
    implicit brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting,
    discoveryConfig: DiscoveryConfig
) extends BootstrapperHandler {
  log.debug("Start as CliqueCoordinator")

  val cliqueCoordinator: ActorRef = context.actorOf(CliqueCoordinator.props(self))

  override def receive: Receive = {
    case c: Tcp.Connected =>
      log.debug(s"Connected to ${c.remoteAddress}")
      cliqueCoordinator.forward(c)
    case Bootstrapper.ForwardConnection =>
      tcpController ! TcpController.WorkFor(cliqueManager.ref)
      context become (awaitInfo orElse forwardConnection)
  }
}

class BrokerBootstrapper(
    val tcpController: ActorRefT[TcpController.Command],
    val cliqueManager: ActorRefT[CliqueManager.Command]
)(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting)
    extends BootstrapperHandler {
  log.debug("Start as Broker")
  val broker: ActorRef = context.actorOf(Broker.props(self))

  override def receive: Receive = awaitInfo
}

class SingleNodeCliqueBootstrapper(val tcpController: ActorRefT[TcpController.Command],
                                   val cliqueManager: ActorRefT[CliqueManager.Command],
                                   intraCliqueInfo: IntraCliqueInfo)
    extends BootstrapperHandler {
  log.debug("Start as single node clique bootstrapper")
  self ! Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo)

  override def receive: Receive = awaitInfo
}

trait BootstrapperHandler extends BaseActor {
  val tcpController: ActorRefT[TcpController.Command]
  val cliqueManager: ActorRefT[CliqueManager.Command]

  override def preStart(): Unit = {
    tcpController ! TcpController.Start(self)
  }

  def awaitInfo: Receive = {
    case Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo) =>
      val cliqueInfo = intraCliqueInfo.cliqueInfo
      tcpController ! TcpController.WorkFor(cliqueManager.ref)
      cliqueManager ! CliqueManager.Start(cliqueInfo)

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
