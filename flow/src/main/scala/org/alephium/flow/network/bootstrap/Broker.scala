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

import akka.actor.{Props, Terminated}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import org.alephium.flow.network.Bootstrapper
import org.alephium.flow.network.broker.{ConnectionHandler, MisbehaviorManager}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.serde.{SerdeResult, Staging}
import org.alephium.util.{ActorRefT, BaseActor, Duration, EventStream, TimeStamp}

object Broker {
  def props(bootstrapper: ActorRefT[Bootstrapper.Command])(implicit
      brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting
  ): Props = Props(new Broker(bootstrapper))

  sealed trait Command
  case object Retry                           extends Command
  final case class Received(message: Message) extends Command

  def connectionProps(remoteAddress: InetSocketAddress, connection: ActorRefT[Tcp.Command])(implicit
      groupConfig: GroupConfig,
      networkSetting: NetworkSetting
  ): Props =
    Props(new MyConnectionHandler(remoteAddress, connection))

  class MyConnectionHandler(
      val remoteAddress: InetSocketAddress,
      val connection: ActorRefT[Tcp.Command]
  )(implicit groupConfig: GroupConfig, val networkSetting: NetworkSetting)
      extends ConnectionHandler[Message] {
    override def tryDeserialize(data: ByteString): SerdeResult[Option[Staging[Message]]] = {
      Message.tryDeserialize(data)
    }

    override def handleNewMessage(message: Message): Unit = {
      context.parent ! Received(message)
    }

    override def handleInvalidMessage(message: MisbehaviorManager.InvalidMessage): Unit = {
      log.debug("Malicious behavior detected in bootstrap, shutdown the system")
      terminateSystem()
    }
  }
}

class Broker(bootstrapper: ActorRefT[Bootstrapper.Command])(implicit
    brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting
) extends BaseActor
    with SerdeUtils
    with EventStream.Publisher {
  val until: TimeStamp = TimeStamp.now() + networkSetting.retryTimeout

  def remoteAddress: InetSocketAddress = networkSetting.coordinatorAddress

  IO(Tcp)(context.system) ! Tcp.Connect(remoteAddress, pullMode = true)

  override def receive: Receive = awaitMaster(until)

  def awaitMaster(until: TimeStamp): Receive = {
    case Broker.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(remoteAddress, pullMode = true)

    case _: Tcp.Connected =>
      log.debug(s"Connected to master: $remoteAddress")
      val connection = sender()
      val connectionHandler = ActorRefT[ConnectionHandler.Command](
        context.actorOf(Broker.connectionProps(remoteAddress, ActorRefT(connection)))
      )
      context watch connectionHandler.ref

      val message = Message.serialize(Message.Peer(PeerInfo.self))
      connectionHandler.ref ! ConnectionHandler.Send(message)
      context become awaitCliqueInfo(connectionHandler)

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = TimeStamp.now()
      if (current isBefore until) {
        scheduleOnce(self, Broker.Retry, Duration.ofSecondsUnsafe(1))
        ()
      } else {
        log.error(s"Cannot connect to ${c.remoteAddress}, shutdown the system")
        terminateSystem()
      }
  }

  def awaitCliqueInfo(connectionHandler: ActorRefT[ConnectionHandler.Command]): Receive = {
    case Broker.Received(clique: Message.Clique) =>
      log.debug(s"Received clique info from master")
      val message = Message.serialize(Message.Ack(brokerConfig.brokerId))
      connectionHandler ! ConnectionHandler.Send(message)
      context become awaitReady(connectionHandler, clique.info)
  }

  def awaitReady(
      connection: ActorRefT[ConnectionHandler.Command],
      cliqueInfo: IntraCliqueInfo
  ): Receive = { case Broker.Received(Message.Ready) =>
    log.debug(s"Clique is ready")
    connection ! ConnectionHandler.CloseConnection
    context become awaitClose(cliqueInfo)
  }

  def awaitClose(cliqueInfo: IntraCliqueInfo): Receive = { case Terminated(_) =>
    log.debug(s"Connection to master ${networkSetting.coordinatorAddress} is closed")
    bootstrapper ! Bootstrapper.SendIntraCliqueInfo(cliqueInfo)
    context.stop(self)
  }

  override def unhandled(message: Any): Unit = {
    super.unhandled(message)
    log.error(s"Unexpected message $message, shutdown the system")
    terminateSystem()
  }
}
