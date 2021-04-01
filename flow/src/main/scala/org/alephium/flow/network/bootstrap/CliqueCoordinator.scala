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

package org.alephium.flow.network.bootstrap

import akka.actor.{Props, Terminated}
import akka.io.Tcp

import org.alephium.flow.network.Bootstrapper
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.SignatureSchema
import org.alephium.protocol.config.BrokerConfig
import org.alephium.serde._
import org.alephium.util.{ActorRefT, BaseActor}

object CliqueCoordinator {
  def props(bootstrapper: ActorRefT[Bootstrapper.Command])(implicit
      brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting
  ): Props =
    Props(new CliqueCoordinator(bootstrapper))

  sealed trait Event
  case object Ready extends Event {
    implicit val serde: Serde[Ready.type] = intSerde.xfmap[Ready.type](
      raw => if (raw == 0) Right(Ready) else Left(SerdeError.wrongFormat(s"Expecting 0 got $raw")),
      _ => 0
    )
  }
}

class CliqueCoordinator(bootstrapper: ActorRefT[Bootstrapper.Command])(implicit
    val brokerConfig: BrokerConfig,
    val networkSetting: NetworkSetting
) extends BaseActor
    with CliqueCoordinatorState {
  override def receive: Receive = awaitBrokers

  val (discoveryPrivateKey, discoveryPublicKey) = SignatureSchema.secureGeneratePriPub()

  def awaitBrokers: Receive = {
    case Tcp.Connected(remote, _) =>
      log.debug(s"Connected to $remote")
      val connection = ActorRefT[Tcp.Command](sender())
      context.actorOf(BrokerConnector.props(remote, connection, self))
      ()
    case info: PeerInfo =>
      log.debug(s"Received broker info from ${info.externalAddress} id: ${info.id}")
      if (addBrokerInfo(info, sender())) {
        context watch sender()
      }
      if (isBrokerInfoFull) {
        log.debug(s"Broadcast clique info")
        bootstrapper ! Bootstrapper.ForwardConnection
        val cliqueInfo = buildCliqueInfo()
        broadcast(BrokerConnector.Send(cliqueInfo))
        context become awaitAck(cliqueInfo)
      }
  }

  def awaitAck(cliqueInfo: IntraCliqueInfo): Receive = { case Message.Ack(id) =>
    log.debug(s"Broker $id is ready")
    if (0 <= id && id < brokerConfig.brokerNum) {
      setReady(id)
      if (isAllReady) {
        log.debug("All the brokers are ready")
        broadcast(CliqueCoordinator.Ready)
        context become awaitTerminated(cliqueInfo)
      }
    }
  }

  def awaitTerminated(cliqueInfo: IntraCliqueInfo): Receive = { case Terminated(actor) =>
    setClose(actor)
    if (isAllClosed) {
      log.debug("All the brokers are closed")
      bootstrapper ! Bootstrapper.SendIntraCliqueInfo(cliqueInfo)
      context stop self
    }
  }
}
