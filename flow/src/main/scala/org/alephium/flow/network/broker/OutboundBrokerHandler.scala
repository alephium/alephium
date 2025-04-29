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

package org.alephium.flow.network.broker

import akka.io.Tcp

import org.alephium.flow.network.{CliqueManager, TcpController}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.message.{Hello, Payload}
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.{ActorRefT, Duration, EventStream}

object OutboundBrokerHandler {
  case object Retry
}

trait OutboundBrokerHandler extends BrokerHandler with EventStream.Publisher {
  val connectionType: ConnectionType = OutboundConnection

  def selfCliqueInfo: CliqueInfo

  implicit def networkSetting: NetworkSetting

  def cliqueManager: ActorRefT[CliqueManager.Command]

  override def preStart(): Unit = {
    super.preStart()
    publishEvent(TcpController.ConnectTo(remoteAddress, ActorRefT(self)))
  }

  var connection: ActorRefT[Tcp.Command]                            = _
  var brokerConnectionHandler: ActorRefT[ConnectionHandler.Command] = _

  override def receive: Receive = connecting

  def connecting: Receive = {
    val backoffStrategy = DefaultBackoffStrategy()

    val receive: Receive = {
      case OutboundBrokerHandler.Retry =>
        publishEvent(TcpController.ConnectTo(remoteAddress, ActorRefT(self)))

      case _: Tcp.Connected =>
        log.info(s"Connected to $remoteAddress")
        connection = networkSetting.connectionBuild(sender())
        brokerConnectionHandler = {
          val ref =
            context.actorOf(ConnectionHandler.clique(remoteAddress, connection, ActorRefT(self)))
          context watch ref
          ActorRefT(ref)
        }
        context become handShaking

      case Tcp.CommandFailed(c: Tcp.Connect) =>
        val retried = backoffStrategy.retry { duration =>
          scheduleOnce(self, OutboundBrokerHandler.Retry, duration)
        }
        if (!retried) {
          log.info(s"Cannot connect to ${c.remoteAddress}")
          context stop self
        }
    }
    receive
  }

  override def handShakeDuration: Duration = networkSetting.handshakeTimeout

  override def handShakeMessage: Payload = {
    Hello.unsafe(selfCliqueInfo.selfInterBrokerInfo, selfCliqueInfo.priKey, selfP2PVersion)
  }

  override def pingFrequency: Duration = networkSetting.pingFrequency
}
