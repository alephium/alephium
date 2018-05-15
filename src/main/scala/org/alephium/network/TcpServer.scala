package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import org.alephium.util.BaseActor

object TcpServer {
  def props(port: Int): Props = Props(new TcpServer(port))
}

class TcpServer(port: Int) extends BaseActor {
  import context.system

  val peerManager: ActorRef = context.parent

  IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress(port))

  override def receive: Receive = binding

  def binding: Receive = {
    case Tcp.Bound(localAddress) =>
      log.debug(s"Server bound to $localAddress")
      context.become(ready)
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.debug(s"Binding failed")
      context stop self
  }

  def ready: Receive = {
    case c: Tcp.Connected =>
      peerManager.forward(c)
  }
}
