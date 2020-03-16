package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}

import org.alephium.util.BaseActor

object TcpServer {
  def props(port: Int): Props = Props(new TcpServer(port))

  sealed trait Command
  final case class Start(bootstrapper: ActorRef) extends Command
  final case class WorkFor(another: ActorRef)    extends Command

  sealed trait Event
  case object Bound extends Event
}

class TcpServer(port: Int) extends BaseActor {
  import context.system

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case TcpServer.Start(bootstrapper) =>
      IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress(port))
      context.become(binding(bootstrapper))
  }

  def binding(bootstrapper: ActorRef): Receive = {
    case Tcp.Bound(localAddress) =>
      log.debug(s"Server bound to $localAddress")
      context.become(workFor(bootstrapper))
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.warning(s"Binding failed")
      context stop self
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def workFor(actor: ActorRef): Receive = {
    case c: Tcp.Connected =>
      actor.forward(c)
    case TcpServer.WorkFor(another) =>
      context become workFor(another)
  }
}
