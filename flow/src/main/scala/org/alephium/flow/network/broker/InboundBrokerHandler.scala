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

import org.alephium.flow.network.CliqueManager
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.message.{Hello, Payload}
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.{ActorRefT, Duration}

trait InboundBrokerHandler extends BrokerHandler {
  val connectionType: ConnectionType = InboundConnection

  def selfCliqueInfo: CliqueInfo

  implicit def networkSetting: NetworkSetting

  def connection: ActorRefT[Tcp.Command]

  def cliqueManager: ActorRefT[CliqueManager.Command]

  override def handShakeDuration: Duration = networkSetting.retryTimeout

  override val brokerConnectionHandler: ActorRefT[ConnectionHandler.Command] = {
    val ref = context.actorOf(ConnectionHandler.clique(remoteAddress, connection, ActorRefT(self)))
    context watch ref
    ActorRefT(ref)
  }

  override def handShakeMessage: Payload = Hello.unsafe(selfCliqueInfo.selfInterBrokerInfo)

  override def pingFrequency: Duration = networkSetting.pingFrequency
}
