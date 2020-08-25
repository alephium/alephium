package org.alephium.flow.network

import java.net.InetSocketAddress

import scala.collection.mutable

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import akka.io.Tcp.Close

import org.alephium.flow.FlowMonitor
import org.alephium.flow.network.broker.BrokerManager
import org.alephium.util.{ActorRefT, BaseActor}

object TcpServer {
  def props(port: Int, brokerManager: ActorRefT[broker.BrokerManager.Command]): Props =
    Props(new TcpServer(port, brokerManager))

  sealed trait Command
  final case class Start(bootstrapper: ActorRef)        extends Command
  final case class ConnectTo(remote: InetSocketAddress) extends Command
  final case class ConnectionConfirmed(c: Tcp.Connected, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class ConnectionDenied(c: Tcp.Connected, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class WorkFor(another: ActorRef) extends Command

  sealed trait Event
  case object Bound extends Event
}

class TcpServer(port: Int, brokerManager: ActorRefT[BrokerManager.Command]) extends BaseActor {
  import context.system

  val tcpManager: ActorRef = IO(Tcp)

  val pendingConnections: mutable.Set[InetSocketAddress] = mutable.Set.empty
  val confirmedConnections: mutable.Map[InetSocketAddress, ActorRefT[Tcp.Command]] =
    mutable.Map.empty

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case TcpServer.Start(bootstrapper) =>
      tcpManager ! Tcp.Bind(self, new InetSocketAddress(port), pullMode = true)
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
      context.system.eventStream.publish(FlowMonitor.Shutdown)
    case TcpServer.WorkFor(another) =>
      context become binding(another)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def workFor(tcpListener: ActorRef, actor: ActorRef): Receive = {
    case c: Tcp.Connected =>
      if (isOutgoing(c.remoteAddress)) {
        confirmConnection(actor, c, sender())
      } else {
        brokerManager ! BrokerManager.ConfirmConnection(c, ActorRefT(sender()))
      }
      tcpListener ! Tcp.ResumeAccepting(batchSize = 1)
    case failure @ Tcp.CommandFailed(c: Tcp.Connect) =>
      pendingConnections -= c.remoteAddress
      log.info(s"Failed to connect to ${c.remoteAddress} - $failure")
      brokerManager ! BrokerManager.Remove(c.remoteAddress)
    case TcpServer.ConnectionConfirmed(c, connection) =>
      confirmConnection(actor, c, connection)
    case TcpServer.ConnectionDenied(c, connection) =>
      pendingConnections -= c.remoteAddress
      connection ! Close
    case TcpServer.ConnectTo(remote) =>
      pendingConnections.addOne(remote)
      tcpManager ! Tcp.Connect(remote, pullMode = true)
    case TcpServer.WorkFor(another) =>
      context become workFor(tcpListener, another)
  }

  def isOutgoing(remoteAddress: InetSocketAddress): Boolean = {
    pendingConnections.contains(remoteAddress)
  }

  def confirmConnection(target: ActorRef,
                        c: Tcp.Connected,
                        connection: ActorRefT[Tcp.Command]): Unit = {
    pendingConnections -= c.remoteAddress
    confirmedConnections += c.remoteAddress -> connection
    target.tell(c, connection.ref)
  }
}
