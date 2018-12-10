package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.{IO, Tcp}
import org.alephium.crypto.Keccak256
import org.alephium.protocol.message.{GetBlocks, Message}
import org.alephium.storage.BlockHandlers
import org.alephium.util.BaseActor

import scala.collection.mutable

object PeerManager {
  def props(port: Int): Props = Props(new PeerManager(port))

  sealed trait Command
  case class SetBlockHandlers(blockhandlers: BlockHandlers)            extends Command
  case class Connect(remote: InetSocketAddress)                        extends Command
  case class Sync(remote: InetSocketAddress, locators: Seq[Keccak256]) extends Command
  case class BroadCast(message: Message, except: Option[ActorRef])     extends Command
  case object GetPeers                                                 extends Command

  object BroadCast {
    def apply(message: Message): BroadCast                   = BroadCast(message, None)
    def apply(message: Message, except: ActorRef): BroadCast = BroadCast(message, Some(except))
  }

  sealed trait Event
  case class Peers(peers: Map[InetSocketAddress, ActorRef]) extends Event
}

class PeerManager(port: Int) extends BaseActor {
  import PeerManager._

  val server: ActorRef                                = context.actorOf(TcpServer.props(port))
  val peers: mutable.Map[InetSocketAddress, ActorRef] = mutable.Map.empty

  def tcpHandlers: Iterable[ActorRef] = peers.values
  def peersSize: Int                  = peers.size

  override def preStart(): Unit = {
    context.watch(server)
    ()
  }

  override def receive: Receive = awaitBlockHandlers orElse {
    case Terminated(child) if child == server =>
      context.unwatch(server)
      context.stop(self)
  }

  def awaitBlockHandlers: Receive = {
    case SetBlockHandlers(blockHandlers) =>
      context.watch(blockHandlers.globalHandler)
      blockHandlers.poolHandlers.flatten.foreach(context.watch)
      context become handleWith(blockHandlers)
  }

  def handleWith(blockHandlers: BlockHandlers): Receive = {
    case Connect(remote) =>
      IO(Tcp)(context.system) ! Tcp.Connect(remote, options = List(Tcp.SO.TcpNoDelay(true)))
    case Tcp.Connected(remote, local) =>
      addPeer(remote, sender(), blockHandlers)
      log.info(s"Connected to $remote, listen at $local, now $peersSize peers")
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
    case BroadCast(message, except) =>
      val toSend = tcpHandlers.filterNot(except.contains)
      log.debug(s"Broadcast message to ${toSend.size} peers")
      val write = TcpHandler.envelope(message)
      toSend.foreach(_ ! write)
    case GetPeers =>
      sender() ! Peers(peers.toMap)
    case Terminated(child) =>
      if (child == server) {
        log.error("Server stopped, stopping peer manager")
        unwatchAndStop(blockHandlers)
      } else if (child == blockHandlers.globalHandler) {
        log.error("Block pool stopped, stopping peer manager")
        unwatchAndStop(blockHandlers)
      } else {
        removePeer(child)
        log.debug(s"Peer connection closed, removing peer, $peersSize peers left")
      }
  }

  def addPeer(remote: InetSocketAddress,
              connection: ActorRef,
              blockHandlers: BlockHandlers): Unit = {
    val tcpHandler = context.actorOf(TcpHandler.props(remote, connection, blockHandlers))
    context.watch(tcpHandler)
    connection ! Tcp.Register(tcpHandler)
//    blockHandler ! BlockHandler.PrepareSync(remote) // TODO: mark tcpHandler in sync status; DoS attack
    peers += (remote -> tcpHandler)
  }

  def removePeer(handler: ActorRef): Unit = {
    val toRemove = peers.view.filter(_._2 == handler).map(_._1)
    toRemove.foreach(peers.remove)
  }

  def unwatchAndStop(blockHandlers: BlockHandlers): Unit = {
    context.unwatch(server)
    context.unwatch(blockHandlers.globalHandler)
    blockHandlers.poolHandlers.flatten.foreach(context.unwatch)
    tcpHandlers.foreach(context.unwatch)
    context.stop(self)
  }
}
