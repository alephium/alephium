package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import org.alephium.crypto.Keccak256
import org.alephium.protocol.message.{GetBlocks, Message}
import org.alephium.storage.BlockPool
import org.alephium.util.BaseActor

object PeerManager {
  def props(port: Int, blockPool: ActorRef): Props = Props(new PeerManager(port, blockPool))

  sealed trait Command
  case class Connect(remote: InetSocketAddress)                        extends Command
  case class BroadCast(message: Message)                               extends Command
  case class Sync(remote: InetSocketAddress, locators: Seq[Keccak256]) extends Command
  case object GetPeers                                                 extends Command

  sealed trait Event
  case class Peers(peers: Map[InetSocketAddress, ActorRef]) extends Event
}

class PeerManager(port: Int, blockPool: ActorRef) extends BaseActor {
  import PeerManager._

  val server: ActorRef = context.actorOf(TcpServer.props(port))

  override def receive: Receive = manage(Map.empty)

  def manage(peers: Map[InetSocketAddress, ActorRef]): Receive = {
    case Connect(remote) =>
      IO(Tcp)(context.system) ! Tcp.Connect(remote)
    case Tcp.Connected(remote, local) =>
      log.debug(s"Connect to $remote, Listen at $local")
      val connection = sender()
      val tcpHandler = context.actorOf(TcpHandler.props(remote, connection, blockPool))
      connection ! Tcp.Register(tcpHandler)
      tcpHandler ! TcpHandler.Start
      val newReceive = manage(peers + (remote -> tcpHandler))
      context.become(newReceive)
      blockPool ! BlockPool.PrepareSync(remote) // TODO: mark tcpHandler in sync status; DoS attack
    case Tcp.CommandFailed(c: Tcp.Connect) =>
      log.info(s"Cannot connect to ${c.remoteAddress}")
    case BroadCast(message) =>
      peers.values.foreach { peer =>
        peer ! message
      }
    case Sync(remote, locators) =>
      if (peers.contains(remote)) {
        val peer = peers(remote)
        log.debug(s"Send GetBlocks to $remote")
        peer ! Message(GetBlocks(locators))
      } else {
        log.warning(s"It's not connected: $remote")
      }
    case GetPeers =>
      sender() ! Peers(peers)
  }
}
