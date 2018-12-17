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
import org.alephium.protocol.model.{GroupIndex, PeerId}
import org.alephium.util.{AVector, BaseActor}

import scala.collection.mutable

object PeerManager {
  def props(builders: TcpHandler.Builder)(implicit config: PlatformConfig): Props =
    Props(new PeerManager(builders))

  sealed trait Command
  case class Set(server: ActorRef, handlers: AllHandlers)       extends Command
  case class Connect(remote: InetSocketAddress, until: Instant) extends Command
  case class Connected(peerId: PeerId, peerInfo: PeerInfo)      extends Command
  case class Sync(peerId: PeerId, locators: AVector[Keccak256]) extends Command
  case class BroadCast(message: Tcp.Write, origin: DataOrigin)  extends Command
  case class Send(message: Tcp.Write, group: GroupIndex)        extends Command
  case object GetPeers                                          extends Command

  sealed trait Event
  case class Peers(peers: Map[PeerId, PeerInfo]) extends Event

  case class PeerInfo(id: PeerId,
                      index: GroupIndex,
                      address: InetSocketAddress,
                      tcpHandler: ActorRef)
  object PeerInfo {
    def apply(id: PeerId, address: InetSocketAddress, tcpHandler: ActorRef)(
        implicit config: PlatformConfig): PeerInfo = {
      PeerInfo(id, id.groupIndex, address, tcpHandler)
    }
  }
}

class PeerManager(builders: TcpHandler.Builder)(implicit config: PlatformConfig) extends BaseActor {
  import PeerManager._

  // Initialized once; use var for performance reason
  var server: ActorRef           = _
  var blockHandlers: AllHandlers = _

  val peers: mutable.Map[PeerId, PeerInfo] = mutable.Map.empty

  def peersSize: Int = peers.size

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
    case Connected(peerId, peerInfo) =>
      context.watch(peerInfo.tcpHandler)
      peers += (peerId -> peerInfo)
      log.info(s"Connected to $peerId@${peerInfo.address}, now $peersSize peers")
    case Tcp.Connected(remote, _) =>
      val connection  = sender()
      val handlerName = BaseActor.envalidActorName(s"TcpHandler-$remote")
      val tcpHandler =
        context.actorOf(builders.createTcpHandler(remote, blockHandlers), handlerName)
      tcpHandler ! TcpHandler.Set(connection)
    case Sync(peerId, locators) =>
      if (peers.contains(peerId)) {
        val peer = peers(peerId).tcpHandler
        log.debug(s"Send GetBlocks to $peerId")
        peer ! Message(GetBlocks(locators))
      } else {
        log.warning(s"No connection to $peerId")
      }
    case BroadCast(message, origin) =>
      val toSend = origin match {
        case DataOrigin.Local          => peers.values
        case DataOrigin.Remote(remote) => peers.filterKeys(_ != remote).values
      }
      log.debug(s"Broadcast message to ${toSend.size} peers")
      toSend.foreach(_.tcpHandler ! message)
    case Send(message, group) =>
      log.debug(s"""Send message to $group; Peers: ${availableGroups.mkString(",")}""")
      val peerInfo = peers.values.filter(_.id.groupIndex == group).head
      peerInfo.tcpHandler ! message
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

  def availableGroups: AVector[GroupIndex] = AVector.from(peers.values.map(_.index))

  def removePeer(tcpHandler: ActorRef): Unit = {
    val toRemove = peers.collectFirst {
      case (id, info) if info.tcpHandler == tcpHandler => id
    }
    toRemove match {
      case Some(remote) =>
        context.unwatch(tcpHandler)
        peers -= remote
      case None =>
        log.debug("The tcpHandler to be removed is not among the peers")
    }
  }

  def unwatchAndStop(): Unit = {
    context.unwatch(server)
    peers.values.foreach(info => context.unwatch(info.tcpHandler))
    context.stop(self)
  }
}
