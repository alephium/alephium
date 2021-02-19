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

import java.net.InetSocketAddress

import scala.collection.mutable

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.{IO, Tcp}
import akka.io.Tcp.Close

import org.alephium.flow.FlowMonitor
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.util.{ActorRefT, BaseActor, EventStream}

object TcpController {
  def props(bindAddress: InetSocketAddress,
            discoveryServer: ActorRefT[DiscoveryServer.Command],
            misbehaviorManager: ActorRefT[broker.MisbehaviorManager.Command]): Props =
    Props(new TcpController(bindAddress, discoveryServer, misbehaviorManager))

  sealed trait Command
  final case class Start(bootstrapper: ActorRef) extends Command
  final case class ConnectTo(remote: InetSocketAddress, forwardTo: ActorRefT[Tcp.Command])
      extends Command
      with EventStream.Event
  final case class ConnectionConfirmed(connected: Tcp.Connected, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class ConnectionDenied(connected: Tcp.Connected, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class WorkFor(another: ActorRef) extends Command

  sealed trait Event
  case object Bound extends Event
}

class TcpController(bindAddress: InetSocketAddress,
                    discoveryServer: ActorRefT[DiscoveryServer.Command],
                    misbehaviorManager: ActorRefT[MisbehaviorManager.Command])
    extends BaseActor
    with EventStream {

  val tcpManager: ActorRef = IO(Tcp)(context.system)

  val pendingOutboundConnections: mutable.Map[InetSocketAddress, ActorRefT[Tcp.Command]] =
    mutable.Map.empty
  val confirmedConnections: mutable.Map[InetSocketAddress, ActorRefT[Tcp.Command]] =
    mutable.Map.empty

  override def preStart(): Unit = {
    subscribe(self, classOf[MisbehaviorManager.PeerBanned])
    subscribe(self, classOf[TcpController.ConnectTo])
  }

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case TcpController.Start(bootstrapper) =>
      tcpManager ! Tcp.Bind(self,
                            bindAddress,
                            pullMode = true,
                            options  = Seq(Tcp.SO.ReuseAddress(true)))
      context.become(binding(bootstrapper))
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def binding(bootstrapper: ActorRef): Receive = {
    case Tcp.Bound(localAddress) =>
      log.debug(s"Server bound to $localAddress")
      sender() ! Tcp.ResumeAccepting(batchSize = 1)
      context.become(workFor(sender(), bootstrapper))
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.error(s"Binding failed")
      publishEvent(FlowMonitor.Shutdown)
    case TcpController.WorkFor(another) =>
      context become binding(another)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def workFor(tcpListener: ActorRef, actor: ActorRef): Receive = {
    case c: Tcp.Connected =>
      pendingOutboundConnections.get(c.remoteAddress) match {
        case Some(outbound) =>
          confirmConnection(outbound.ref, c, sender())
        case None =>
          log.debug(s"Ask connection confirmation for $c")
          misbehaviorManager ! MisbehaviorManager.ConfirmConnection(c, ActorRefT(sender()))
      }
      tcpListener ! Tcp.ResumeAccepting(batchSize = 1)
    case failure @ Tcp.CommandFailed(c: Tcp.Connect) =>
      pendingOutboundConnections -= c.remoteAddress
      log.info(s"Failed to connect to ${c.remoteAddress} - $failure")
      discoveryServer ! DiscoveryServer.Remove(c.remoteAddress)
    case TcpController.ConnectionConfirmed(connected, connection) =>
      confirmConnection(actor, connected, connection)
    case TcpController.ConnectionDenied(connected, connection) =>
      pendingOutboundConnections -= connected.remoteAddress
      connection ! Close
    case TcpController.ConnectTo(remote, forwardTo) =>
      pendingOutboundConnections.update(remote, forwardTo)
      tcpManager ! Tcp.Connect(remote, pullMode = true)
    case TcpController.WorkFor(another) =>
      context become workFor(tcpListener, another)
    case Tcp.Closed =>
      ()
    case Terminated(connection) =>
      val toRemove = confirmedConnections.filter(_._2 == ActorRefT[Tcp.Command](connection)).keys
      toRemove.foreach(confirmedConnections -= _)
    case MisbehaviorManager.PeerBanned(remote) =>
      confirmedConnections.get(remote) match {
        case Some(connection) =>
          connection ! Close
          confirmedConnections -= remote
          log.debug(s"Closing connection $remote")
        case None =>
          log.warning(s"$remote is banned, but doesn't belong to our confirmed connection")
      }

  }

  def confirmConnection(target: ActorRef,
                        connected: Tcp.Connected,
                        connection: ActorRefT[Tcp.Command]): Unit = {
    pendingOutboundConnections -= connected.remoteAddress
    confirmedConnections += connected.remoteAddress -> connection
    context watch connection.ref
    target.tell(connected, connection.ref)
  }
}
