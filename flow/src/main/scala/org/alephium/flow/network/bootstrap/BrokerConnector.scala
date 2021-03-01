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

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.FlowMonitor
import org.alephium.flow.network.broker.{ConnectionHandler, MisbehaviorManager}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._
import org.alephium.util.{ActorRefT, BaseActor, EventStream}

object BrokerConnector {
  def props(remoteAddress: InetSocketAddress,
            connection: ActorRefT[Tcp.Command],
            cliqueCoordinator: ActorRef)(implicit groupConfig: GroupConfig,
                                         networkSetting: NetworkSetting): Props =
    Props(new BrokerConnector(remoteAddress, connection, cliqueCoordinator))

  sealed trait Command
  final case class Received(message: Message)             extends Command
  final case class Send(intraCliqueInfo: IntraCliqueInfo) extends Command

  def connectionProps(remoteAddress: InetSocketAddress, connection: ActorRefT[Tcp.Command])(
      implicit groupConfig: GroupConfig,
      networkSetting: NetworkSetting): Props =
    Props(new MyConnectionHandler(remoteAddress, connection))

  class MyConnectionHandler(val remoteAddress: InetSocketAddress,
                            val connection: ActorRefT[Tcp.Command])(
      implicit groupConfig: GroupConfig,
      val networkSetting: NetworkSetting)
      extends ConnectionHandler[Message] {
    override def tryDeserialize(data: ByteString): SerdeResult[Option[Staging[Message]]] = {
      Message.tryDeserialize(data)
    }

    override def handleNewMessage(message: Message): Unit = {
      context.parent ! Received(message)
    }

    override def handleInvalidMessage(message: MisbehaviorManager.InvalidMessage): Unit = {
      log.debug("Malicious behavior detected in bootstrap, shutdown the system")
      publishEvent(FlowMonitor.Shutdown)
    }
  }
}

class BrokerConnector(remoteAddress: InetSocketAddress,
                      connection: ActorRefT[Tcp.Command],
                      cliqueCoordinator: ActorRef)(implicit val groupConfig: GroupConfig,
                                                   networkSetting: NetworkSetting)
    extends BaseActor
    with SerdeUtils
    with EventStream.Publisher {
  import BrokerConnector._

  val connectionHandler: ActorRefT[ConnectionHandler.Command] =
    ActorRefT(context.actorOf(connectionProps(remoteAddress, connection)))
  context watch connectionHandler.ref

  override def receive: Receive = {
    case Received(peer: Message.Peer) =>
      cliqueCoordinator ! peer.info
      context become forwardCliqueInfo
  }

  def forwardCliqueInfo: Receive = {
    case Send(cliqueInfo) =>
      val data = Message.serialize(Message.Clique(cliqueInfo))
      connectionHandler ! ConnectionHandler.Send(data)
      context become awaitAck
  }

  def awaitAck: Receive = {
    case Received(ack) =>
      cliqueCoordinator ! ack
      context become forwardReady
  }

  def forwardReady: Receive = {
    case CliqueCoordinator.Ready =>
      log.debug("Clique is ready")
      val data = Message.serialize(Message.Ready)
      connectionHandler ! ConnectionHandler.Send(data)
    case Terminated(_) =>
      log.debug(s"Connection to broker is closed")
      context stop self
  }

  override def unhandled(message: Any): Unit = {
    super.unhandled(message)
    log.debug(s"Unexpected message, shutdown the system")
    publishEvent(FlowMonitor.Shutdown)
  }
}
