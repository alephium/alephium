package org.alephium.flow.network

import java.net.InetSocketAddress

import scala.collection.mutable

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.{IO, Tcp}
import akka.io.Tcp.Close

import org.alephium.flow.FlowMonitor
import org.alephium.flow.network.broker.BrokerManager
import org.alephium.util.{ActorRefT, BaseActor}

object TcpController {
  def props(bindAddress: InetSocketAddress,
            brokerManager: ActorRefT[broker.BrokerManager.Command]): Props =
    Props(new TcpController(bindAddress, brokerManager))

  sealed trait Command
  final case class Start(bootstrapper: ActorRef)        extends Command
  final case class ConnectTo(remote: InetSocketAddress) extends Command
  final case class ConnectionConfirmed(connected: Tcp.Connected, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class ConnectionDenied(connected: Tcp.Connected, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class WorkFor(another: ActorRef) extends Command

  sealed trait Event
  case object Bound extends Event
}

class TcpController(bindAddress: InetSocketAddress, brokerManager: ActorRefT[BrokerManager.Command])
    extends BaseActor {
  import context.system

  val tcpManager: ActorRef = IO(Tcp)

  val pendingConnections: mutable.Set[InetSocketAddress] = mutable.Set.empty
  val confirmedConnections: mutable.Map[InetSocketAddress, ActorRefT[Tcp.Command]] =
    mutable.Map.empty

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case TcpController.Start(bootstrapper) =>
      tcpManager ! Tcp.Bind(self, bindAddress, pullMode = true)
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
    case TcpController.ConnectionConfirmed(connected, connection) =>
      confirmConnection(actor, connected, connection)
    case TcpController.ConnectionDenied(connected, connection) =>
      pendingConnections -= connected.remoteAddress
      connection ! Close
    case TcpController.ConnectTo(remote) =>
      pendingConnections.addOne(remote)
      tcpManager ! Tcp.Connect(remote, pullMode = true)
    case TcpController.WorkFor(another) =>
      context become workFor(tcpListener, another)
    case Tcp.Closed =>
      ()
    case Terminated(connection) =>
      val toRemove = confirmedConnections.filter(_._2 == ActorRefT[Tcp.Command](connection)).keys
      toRemove.foreach(confirmedConnections -= _)
  }

  def isOutgoing(remoteAddress: InetSocketAddress): Boolean = {
    pendingConnections.contains(remoteAddress)
  }

  def confirmConnection(target: ActorRef,
                        connected: Tcp.Connected,
                        connection: ActorRefT[Tcp.Command]): Unit = {
    pendingConnections -= connected.remoteAddress
    confirmedConnections += connected.remoteAddress -> connection
    context watch connection.ref
    target.tell(connected, connection.ref)
  }
}
