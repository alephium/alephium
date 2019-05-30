package org.alephium.flow.network

import java.net.InetSocketAddress
import java.time.Instant

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.{Block, BlockHeader, GroupIndex, PeerId}
import org.alephium.util.{AVector, BaseActor}

import scala.concurrent.duration._

object PeerManager {
  def props(builders: TcpHandler.Builder)(implicit config: PlatformConfig): Props =
    Props(new PeerManager(builders))

  sealed trait Command
  case class Connect(remote: InetSocketAddress, until: Instant) extends Command
  case class Connected(peerId: PeerId, peerInfo: PeerInfo)      extends Command
  case object GetPeers                                          extends Command
  case class BroadCastHeader(
      header: BlockHeader,
      headerMsg: Tcp.Write,
      origin: DataOrigin
  ) extends Command
  case class BroadCastBlock(
      block: Block,
      blockMsg: Tcp.Write,
      headerMsg: Tcp.Write,
      origin: DataOrigin
  ) extends Command

  case class Set(server: ActorRef, handlers: AllHandlers, discoveryServer: ActorRef) extends Command

  sealed trait Event
  case class Peers(peers: AVector[AVector[PeerInfo]]) extends Event

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

class PeerManager(builders: TcpHandler.Builder)(implicit val config: PlatformConfig)
    extends BaseActor
    with PeerManagerState {
  import PeerManager._

  // Initialized once; use var for performance reason
  var server: ActorRef           = _
  var blockHandlers: AllHandlers = _
  var discoveryServer: ActorRef  = _

  override def receive: Receive = awaitInit

  def awaitInit: Receive = {
    case Set(_server, _blockHandlers, _discoveryServer) =>
      server          = _server
      blockHandlers   = _blockHandlers
      discoveryServer = _discoveryServer

      server ! TcpServer.Start
      context.watch(server)

      discoveryServer ! DiscoveryServer.GetPeers
      context.become(awaitPeers orElse handle)
  }

  def awaitPeers: Receive = {
    case DiscoveryServer.Peers(discoveried) =>
      log.info(s"Discovered #${discoveried.sumBy(_.length)} peers")
      val isEnough = discoveried.forallWithIndex { (peers, i) =>
        i == config.mainGroup.value || peers.length >= 1
      }
      if (isEnough) {
        val until = Instant.now().plusMillis(config.retryTimeout.toMillis)
        discoveried.foreach { ps =>
          ps.foreach(peer => self ! Connect(peer.socketAddress, until))
        }
      } else {
        scheduleOnce(discoveryServer, DiscoveryServer.GetPeers, 1.second)
      }
  }

  def handle: Receive = {
    case Connect(remote, until) =>
      val handlerName = BaseActor.envalidActorName(s"TcpHandler-$remote")
      val tcpHandler =
        context.actorOf(builders.createTcpHandler(remote, blockHandlers), handlerName)
      tcpHandler ! TcpHandler.Connect(until)
    case Connected(peerId, peerInfo) =>
      context.watch(peerInfo.tcpHandler)
      addPeer(peerInfo)
      log.info(s"Connected to $peerId@${peerInfo.address} ($peersSize peers in total)")
    case Tcp.Connected(remote, _) =>
      val connection  = sender()
      val handlerName = BaseActor.envalidActorName(s"TcpHandler-$remote")
      val tcpHandler =
        context.actorOf(builders.createTcpHandler(remote, blockHandlers), handlerName)
      tcpHandler ! TcpHandler.Set(connection)
    case BroadCastBlock(block, blockMsg, headerMsg, origin) =>
      assert(block.chainIndex.relateTo(config.mainGroup))
      log.debug(s"Broadcasting block/header to peers")
      broadcastBlock(block, blockMsg, headerMsg, origin)
    case BroadCastHeader(header, _, _) =>
      // Note: broadcast header only after we introduce cliques
      assert(!header.chainIndex.relateTo(config.mainGroup))
    case GetPeers =>
      sender() ! Peers(getPeers)
    case Terminated(child) =>
      if (child == server) {
        log.error("Server stopped, stopping peer manager")
        unwatchAndStop()
      } else {
        removePeer(child)
        log.debug(s"Peer connection closed, removing peer ($peersSize peers left)")
      }
  }

  def broadcastBlock(block: Block,
                     blockMsg: Tcp.Write,
                     headerMsg: Tcp.Write,
                     origin: DataOrigin): Unit = {
    val blockIndex = block.chainIndex
    traversePeers { pi =>
      if (origin.isNot(pi.id)) {
        if (blockIndex.relateTo(pi.id.groupIndex)) pi.tcpHandler ! blockMsg
        else pi.tcpHandler ! headerMsg
      }
    }
  }

  def broadcastHeader(header: BlockHeader, headerMsg: Tcp.Write, origin: DataOrigin): Unit = {
    val headerIndex = header.chainIndex
    traversePeers { pi =>
      if (origin.isNot(pi.id)) {
        if (!headerIndex.relateTo(pi.id.groupIndex)) pi.tcpHandler ! headerMsg
      }
    }
  }

  def removePeer(tcpHandler: ActorRef): Unit = {
    traversePeers { peerInfo =>
      if (peerInfo.tcpHandler == tcpHandler) {
        context.unwatch(tcpHandler)
        removePeer(peerInfo.id)
      }
    }
  }

  def unwatchAndStop(): Unit = {
    context.unwatch(server)
    traversePeers(info => context.unwatch(info.tcpHandler))
    context.stop(self)
  }
}
