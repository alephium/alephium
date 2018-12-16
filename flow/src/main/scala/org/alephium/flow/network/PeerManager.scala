package org.alephium.flow.network

import java.net.InetSocketAddress
import java.time.Instant

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.Tcp
import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.message.{GetBlocks, Message}
import org.alephium.protocol.model.PeerId
import org.alephium.util.{AVector, BaseActor}

import scala.collection.mutable

object PeerManager {
  def props(builders: TcpHandler.Builder)(implicit config: PlatformConfig): Props =
    Props(new PeerManager(builders))

  sealed trait Command
  case class Set(server: ActorRef, blockhandlers: AllHandlers)  extends Command
  case class Connect(remote: InetSocketAddress, until: Instant) extends Command
  case class Connected(peerId: PeerId, tcpHandler: ActorRef)    extends Command
  case class Sync(peerId: PeerId, locators: AVector[Keccak256]) extends Command
  case class BroadCast(message: Message, origin: DataOrigin)    extends Command
  case object GetPeers                                          extends Command

  sealed trait Event
  case class Peers(peers: Map[PeerId, ActorRef]) extends Event
}

class PeerManager(builders: TcpHandler.Builder)(implicit config: PlatformConfig) extends BaseActor {
  import PeerManager._

  // Initialized once; use var for performance reason
  var server: ActorRef           = _
  var blockHandlers: AllHandlers = _

  val peers: mutable.Map[PeerId, ActorRef] = mutable.Map.empty

  def tcpHandlers: Iterable[ActorRef] = peers.values
  def peersSize: Int                  = peers.size

  override def receive: Receive = awaitInit

  def awaitInit: Receive = {
    case Set(_server, _blockHandlers) =>
      server        = _server
      blockHandlers = _blockHandlers

      server ! TcpServer.Start
      context.watch(server)
      context.become(handle)
  }

  def handle: Receive = {
    case Connect(remote, until) =>
      val handlerName = BaseActor.envalidActorName(s"TcpHandler-$remote")
      val tcpHandler =
        context.actorOf(builders.createTcpHandler(remote, blockHandlers), handlerName)
      tcpHandler ! TcpHandler.Connect(until)
    case Connected(peerId, tcpHandler) =>
      addPeerWithHandler(peerId, tcpHandler)
    case Tcp.Connected(remote, _) =>
      val connection  = sender()
      val handlerName = BaseActor.envalidActorName(s"TcpHandler-$remote")
      val tcpHandler =
        context.actorOf(builders.createTcpHandler(remote, blockHandlers), handlerName)
      tcpHandler ! TcpHandler.Set(connection)
    case Sync(peerId, locators) =>
      if (peers.contains(peerId)) {
        val peer = peers(peerId)
        log.debug(s"Send GetBlocks to $peerId")
        peer ! Message(GetBlocks(locators))
      } else {
        log.warning(s"No connection to $peerId")
      }
    case BroadCast(message, origin) =>
      val toSend = origin match {
        case DataOrigin.Local          => tcpHandlers
        case DataOrigin.Remote(remote) => peers.filterKeys(_ != remote).values
      }
      log.debug(s"Broadcast message to ${toSend.size} peers")
      val write = TcpHandler.envelope(message)
      toSend.foreach(_ ! write)
    case GetPeers =>
      sender() ! Peers(peers.toMap)
    case Terminated(child) =>
      if (child == server) {
        log.error("Server stopped, stopping peer manager")
        unwatchAndStop()
      } else {
        removePeer(child)
        log.debug(s"Peer connection closed, removing peer, $peersSize peers left")
      }
  }

  def addPeerWithHandler(peerId: PeerId, tcpHandler: ActorRef): Unit = {
    context.watch(tcpHandler)
//    blockHandler ! BlockHandler.PrepareSync(remote) // TODO: mark tcpHandler in sync status; DoS attack
    peers += (peerId -> tcpHandler)
    log.info(s"Connected to $peerId, now $peersSize peers")
  }

  def removePeer(handler: ActorRef): Unit = {
    val toRemove = peers.view.filter(_._2 == handler)
    toRemove.foreach {
      case (remote, tcpHandler) =>
        context.unwatch(tcpHandler)
        peers.remove(remote)
    }
  }

  def unwatchAndStop(): Unit = {
    context.unwatch(server)
    tcpHandlers.foreach(context.unwatch)
    context.stop(self)
  }
}
