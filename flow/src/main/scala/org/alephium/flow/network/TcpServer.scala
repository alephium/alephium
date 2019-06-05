package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import org.alephium.util.BaseActor

object TcpServer {
  def props(port: Int): Props = Props(new TcpServer(port))

  sealed trait Command
  case object Start extends Command

  sealed trait Event
  case object Bound extends Event
}

class TcpServer(port: Int) extends BaseActor {
  import context.system

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case TcpServer.Start =>
      IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress(port))
      context.become(binding)
  }

  def binding: Receive = {
    case Tcp.Bound(localAddress) =>
      log.debug(s"Server bound to $localAddress")
      context.become(awaitForActor())
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.warning(s"Binding failed")
      context stop self
  }

  def awaitForActor(): Receive = {
    case actor: ActorRef =>
      context become workFor(actor)
  }

  def workFor(actor: ActorRef): Receive = {
    case c: Tcp.Connected =>
      actor.forward(c)
    case another: ActorRef =>
      context become workFor(another)
  }
}
