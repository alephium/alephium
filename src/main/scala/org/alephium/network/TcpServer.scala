package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import org.alephium.util.BaseActor

object TcpServer {
  def props(port: Int, blockHandler: ActorRef): Props = Props(new TcpServer(port, blockHandler))
}

class TcpServer(port: Int, blockHandler: ActorRef) extends BaseActor {

  import context.system

  IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress("localhost", port))

  override def receive: Receive = binding

  def binding: Receive = {
    case Tcp.Bound(localAddress) =>
      logger.debug(s"Server binded to $localAddress")
      context.become(ready)
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      logger.debug(s"Bind failed")
      context stop self
  }

  def ready: Receive = {
    case Tcp.Connected(remoteAddress, localAddress) =>
      logger.debug(s"Connect to $remoteAddress, Listen at $localAddress")
      val connection = sender()
      val handler = context.actorOf(SimpleTcpHandler.props(remoteAddress, connection, blockHandler),
                                    s"${localAddress.getPort}-${remoteAddress.getPort}")
      connection ! Tcp.Register(handler)
      handler ! TcpHandler.Start
  }
}
