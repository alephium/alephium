package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.{IO, Tcp}
import org.alephium.crypto.Keccak256
import org.alephium.protocol.message.{GetBlocks, Message}
import org.alephium.storage.BlockPool
import org.alephium.util.BaseActor

object PeerManager {
  def props(port: Int, blockPool: ActorRef): Props = Props(new PeerManager(port, blockPool))

  sealed trait Command
  case class Connect(remote: InetSocketAddress)                        extends Command
  case class Sync(remote: InetSocketAddress, locators: Seq[Keccak256]) extends Command
  case object GetPeers                                                 extends Command

  sealed trait Event
  case class Peers(peers: Map[InetSocketAddress, ActorRef]) extends Event
}

class PeerManager(port: Int, blockPool: ActorRef) extends BaseActor {
  import PeerManager._

  val server: ActorRef = context.actorOf(TcpServer.props(port))

  override def preStart(): Unit = {
    context.watch(server)
    context.watch(blockPool)
    ()
  }

  override def receive: Receive = manage(Map.empty)

  def manage(peers: Map[InetSocketAddress, ActorRef]): Receive = {
    case Connect(remote) =>
      IO(Tcp)(context.system) ! Tcp.Connect(remote)
    case Tcp.Connected(remote, local) =>
      val morePeers = addPeer(peers, remote, sender())
      log.debug(s"Connected to $remote, listen at $local, now ${morePeers.size} peers")
      context.become(manage(morePeers))
    case Tcp.CommandFailed(c: Tcp.Connect) =>
      log.info(s"Cannot connect to ${c.remoteAddress}")
    case Sync(remote, locators) =>
      if (peers.contains(remote)) {
        val peer = peers(remote)
        log.debug(s"Send GetBlocks to $remote")
        peer ! Message(GetBlocks(locators))
      } else {
        log.warning(s"No connection to $remote")
      }
    case GetPeers =>
      sender() ! Peers(peers)
    case Terminated(child) =>
      if (child == server) {
        log.error("Server stopped, stopping peer manager")
        unwatchAndStop(peers)
      } else if (child == blockPool) {
        log.error("Block pool stopped, stopping peer manager")
        unwatchAndStop(peers)
      } else {
        val restPeers = removePeer(peers, child)
        log.debug(s"Peer connection closed, removing peer, ${restPeers.size} peers left")
        context become manage(restPeers)
      }
  }

  def addPeer(peers: Map[InetSocketAddress, ActorRef],
              remote: InetSocketAddress,
              connection: ActorRef): Map[InetSocketAddress, ActorRef] = {
    val tcpHandler = context.actorOf(TcpHandler.props(remote, connection, blockPool))
    context.watch(tcpHandler)
    connection ! Tcp.Register(tcpHandler)
    blockPool ! BlockPool.PrepareSync(remote) // TODO: mark tcpHandler in sync status; DoS attack
    peers + (remote -> tcpHandler)
  }

  def removePeer(peers: Map[InetSocketAddress, ActorRef],
                 handler: ActorRef): Map[InetSocketAddress, ActorRef] = {
    val (remove, rest) = peers.partition(_._2 == handler)
    remove.values.foreach(context.unwatch)
    rest
  }

  def unwatchAndStop(peers: Map[InetSocketAddress, ActorRef]): Unit = {
    context.unwatch(server)
    context.unwatch(blockPool)
    peers.values.foreach(context.unwatch)
    context.stop(self)
  }
}
