package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import org.alephium.util.BaseActor

object TcpServer {
  def props(port: Int, peerManager: ActorRef): Props = Props(new TcpServer(port, peerManager))

  sealed trait Command
  case object Start extends Command
}

class TcpServer(port: Int, peerManager: ActorRef) extends BaseActor {
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
      context.become(ready)
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.warning(s"Binding failed")
      context stop self
  }

  def ready: Receive = {
    case c: Tcp.Connected =>
      peerManager.forward(c)
  }
}
