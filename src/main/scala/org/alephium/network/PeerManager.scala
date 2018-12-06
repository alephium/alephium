package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import org.alephium.util.BaseActor

object PeerManager {
  def props(blockPool: ActorRef): Props = Props(new PeerManager(blockPool))

  sealed trait Command
  case class Connect(remote: InetSocketAddress) extends Command
  case object GetPeers                          extends Command

  sealed trait Event
  case class Peers(peers: Map[InetSocketAddress, ActorRef]) extends Event
}

class PeerManager(blockPool: ActorRef) extends BaseActor {
  import PeerManager._

  override def receive: Receive = manage(Map.empty)

  def manage(peers: Map[InetSocketAddress, ActorRef]): Receive = {
    case Connect(remote) =>
      IO(Tcp)(context.system) ! Tcp.Connect(remote)
    case GetPeers =>
      sender() ! Peers(peers)
    case Tcp.Connected(remote, local) =>
      log.debug(s"Connect to $remote, Listen at $local")
      val connection = sender()
      val tcpHandler = context.actorOf(TcpHandler.props(remote, connection, blockPool))
      connection ! Tcp.Register(tcpHandler)
      val newReceive = manage(peers + (remote -> tcpHandler))
      context.become(newReceive)
    case Tcp.CommandFailed(x: Tcp.Connect) =>
      log.info(s"Cannot connect to ${x.remoteAddress}")
  }
}
