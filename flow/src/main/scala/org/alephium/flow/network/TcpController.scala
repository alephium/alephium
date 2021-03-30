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

import java.net.{InetAddress, InetSocketAddress}

import scala.collection.mutable

import akka.actor.{ActorRef, Props, Stash, Terminated}
import akka.io.{IO, Tcp}

import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.setting.NetworkSetting
import org.alephium.util.{ActorRefT, BaseActor, EventStream}

object TcpController {
  def props(
      bindAddress: InetSocketAddress,
      misbehaviorManager: ActorRefT[broker.MisbehaviorManager.Command]
  )(implicit networkSetting: NetworkSetting): Props =
    Props(new TcpController(bindAddress, misbehaviorManager))

  sealed trait Command
  final case class Start(bootstrapper: ActorRef) extends Command
  final case class ConnectTo(remote: InetSocketAddress, forwardTo: ActorRefT[Tcp.Event])
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

class TcpController(
    bindAddress: InetSocketAddress,
    misbehaviorManager: ActorRefT[MisbehaviorManager.Command]
)(implicit networkSetting: NetworkSetting)
    extends BaseActor
    with Stash
    with EventStream {

  val tcpManager: ActorRef = IO(Tcp)(context.system)

  val pendingOutboundConnections: mutable.Map[InetSocketAddress, ActorRefT[Tcp.Event]] =
    mutable.Map.empty
  val confirmedConnections: mutable.HashMap[InetSocketAddress, ActorRefT[Tcp.Command]] =
    mutable.HashMap.empty

  override def preStart(): Unit = {
    subscribeEvent(self, classOf[MisbehaviorManager.PeerBanned])
    subscribeEvent(self, classOf[TcpController.ConnectTo])
  }

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case TcpController.Start(bootstrapper) =>
      tcpManager ! Tcp.Bind(
        self,
        bindAddress,
        pullMode = true,
        options = Seq(Tcp.SO.ReuseAddress(true))
      )
      context.become(binding(bootstrapper))

    case _ => stash()
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def binding(bootstrapper: ActorRef): Receive = {
    case Tcp.Bound(localAddress) =>
      log.debug(s"Server bound to $localAddress")
      sender() ! Tcp.ResumeAccepting(batchSize = 1)
      unstashAll()
      context.become(workFor(sender(), bootstrapper))
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.error(s"Binding failed")
      terminateSystem()
    case TcpController.WorkFor(another) =>
      context become binding(another)
    case _ => stash()
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def workFor(tcpListener: ActorRef, actor: ActorRef): Receive = {
    case c: Tcp.Connected =>
      val connection = networkSetting.connectionBuild(sender())
      pendingOutboundConnections.get(c.remoteAddress) match {
        case Some(outbound) =>
          confirmConnection(outbound.ref, c, connection)
        case None =>
          log.debug(s"Ask connection confirmation for $c")
          misbehaviorManager ! MisbehaviorManager.ConfirmConnection(c, connection)
      }
      tcpListener ! Tcp.ResumeAccepting(batchSize = 1)
    case failure @ Tcp.CommandFailed(c: Tcp.Connect) =>
      pendingOutboundConnections.get(c.remoteAddress).foreach { forwardTo =>
        log.debug(s"Failed to connect to ${c.remoteAddress} - $failure")
        pendingOutboundConnections -= c.remoteAddress
        forwardTo.forward(failure)
      }
    case TcpController.ConnectionConfirmed(connected, connection) =>
      confirmConnection(actor, connected, connection)
    case TcpController.ConnectionDenied(connected, connection) =>
      log.debug(s"Connection with ${connected.remoteAddress} is denied")
      pendingOutboundConnections -= connected.remoteAddress
      connection ! Tcp.Abort
    case TcpController.ConnectTo(remote, forwardTo) =>
      pendingOutboundConnections.update(remote, forwardTo)
      tcpManager ! Tcp.Connect(remote, pullMode = true)
    case TcpController.WorkFor(another) =>
      context become workFor(tcpListener, another)
    case _: Tcp.ConnectionClosed =>
      ()
    case Terminated(connection) =>
      removeConnection(connection)
    case MisbehaviorManager.PeerBanned(bannedAddress) =>
      handleBannedPeer(bannedAddress)
  }

  def confirmConnection(
      target: ActorRef,
      connected: Tcp.Connected,
      connection: ActorRefT[Tcp.Command]
  ): Unit = {
    pendingOutboundConnections -= connected.remoteAddress
    confirmedConnections += connected.remoteAddress -> connection
    context watch connection.ref
    target.tell(connected, connection.ref)
  }

  def removeConnection(connection: ActorRef): Unit = {
    confirmedConnections.filterInPlace((_, y) => y.ref != connection)
  }

  def handleBannedPeer(bannedAddress: InetAddress): Unit = {
    confirmedConnections.filterInPlace { case (socketAddress, connection) =>
      val shouldKeep = socketAddress.getAddress != bannedAddress
      if (!shouldKeep) {
        connection ! Tcp.Abort
        log.debug(s"Closing connection with $bannedAddress")
      }
      shouldKeep
    }
  }
}
