// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp

import org.alephium.flow.network.bootstrap.{Broker, CliqueCoordinator, IntraCliqueInfo, PeerInfo}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig}
import org.alephium.protocol.model.CliqueId
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

  val cliqueCoordinator: ActorRef = context.actorOf(CliqueCoordinator.props(ActorRefT(self)))

  override def receive: Receive = {
    case c: Tcp.Connected =>
      log.debug(s"Connected to ${c.remoteAddress}")
      cliqueCoordinator.forward(c)
    case Bootstrapper.ForwardConnection =>
      tcpController ! TcpController.WorkFor(cliqueManager.ref)
      context become awaitInfoWithForward
  }
}

class BrokerBootstrapper(
    val tcpController: ActorRefT[TcpController.Command],
    val cliqueManager: ActorRefT[CliqueManager.Command]
)(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting)
    extends BootstrapperHandler {
  log.debug("Start as Broker")
  val broker: ActorRef = context.actorOf(Broker.props(ActorRefT(self)))

  override def receive: Receive = awaitInfoWithForward
}

class SingleNodeCliqueBootstrapper(val tcpController: ActorRefT[TcpController.Command],
                                   val cliqueManager: ActorRefT[CliqueManager.Command],
                                   intraCliqueInfo: IntraCliqueInfo)
    extends BootstrapperHandler {
  log.debug("Start as single node clique bootstrapper")
  self ! Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo)

  override def receive: Receive = awaitInfoWithForward
}

trait BootstrapperHandler extends BaseActor {
  val tcpController: ActorRefT[TcpController.Command]
  val cliqueManager: ActorRefT[CliqueManager.Command]

  override def preStart(): Unit = {
    tcpController ! TcpController.Start(self)
  }

  def awaitInfoWithForward: Receive = awaitInfo orElse forwardConnection

  private def awaitInfo: Receive = {
    case Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo) =>
      tcpController ! TcpController.WorkFor(cliqueManager.ref)
      cliqueManager ! CliqueManager.Start(intraCliqueInfo.cliqueInfo)

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
