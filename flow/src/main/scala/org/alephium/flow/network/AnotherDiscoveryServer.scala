package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.{IO, Udp}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage._
import org.alephium.protocol.model.{PeerId, PeerInfo}
import org.alephium.util.{AVector, BaseActor}

import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

object AnotherDiscoveryServer {
  def props(bootstrap: AVector[PeerInfo])(implicit config: DiscoveryConfig): Props =
    Props(new AnotherDiscoveryServer(bootstrap))

  case class PeerStatus(info: PeerInfo, updateAt: Long)
  object PeerStatus {
    def fromInfo(info: PeerInfo): PeerStatus = {
      val now = System.currentTimeMillis
      PeerStatus(info, now)
    }
  }

  trait PendingStatus {
    def pingAt: Long
  }
  case class AwaitPong(pingAt: Long)                       extends PendingStatus
  case class FindNodeReceived(msg: FindNode, pingAt: Long) extends PendingStatus // awaiting pong

  sealed trait Command

  sealed trait ExternalCommand       extends Command
  case object GetPeers               extends ExternalCommand
  case class Disable(peerId: PeerId) extends ExternalCommand

  sealed trait InternalCommand extends Command
  case object Scan             extends InternalCommand

  sealed trait Event
  case class Peers(peers: AVector[AVector[PeerInfo]]) extends Event
}

/*
 * This variant of Kademlia protocol follows these rules:
 *  -> send ping to another peer to detect the liveness
 *  -> pong back when received valid ping
 *  -> ping back to verify peer address when received ping for the first time from another peer
 *  -> send find_node to newly discovered peer (i.e. received a valid pong from that peer)
 *  -> send neighbors back when received find_node from valid peer
 *  -> ping all the new neighbors received from peers
 *  -> fetch neighbors periodically from peers
 *
 *
 *  TODO: each group has several buckets instead of just one bucket
 */
class AnotherDiscoveryServer(bootstrap: AVector[PeerInfo])(implicit val config: DiscoveryConfig)
    extends BaseActor
    with DiscoveryServerState {
  import AnotherDiscoveryServer._
  import context.system
  import context.dispatcher

  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(config.udpPort))

  def receive: Receive = binding orElse handleExternal

  def binding: Receive = {
    case Udp.Bound(_) =>
      log.debug(s"UDP server bound successfully")
      setSocket(sender())
      bootstrap.foreach(peer => tryPing(peer))
      system.scheduler.schedule(Duration.Zero, config.scanFrequency, self, Scan)
      context.become(ready)

    case Udp.CommandFailed(bind: Udp.Bind) =>
      log.error(s"Could not bind the UDP socket ($bind)")
      context stop self
  }

  def ready: Receive = handleData orElse handleInternal orElse handleExternal

  def handleExternal: Receive = {
    case command: ExternalCommand => _handleExternal(command)
  }

  def _handleExternal(command: ExternalCommand): Unit = command match {
    case GetPeers =>
      sender() ! Peers(getActivePeers)
    case Disable(peerId) =>
      getBucket(peerId) -= peerId
      ()
  }

  def handleInternal: Receive = {
    case command: InternalCommand => _handleInternal(command)
  }

  def _handleInternal(command: InternalCommand): Unit = command match {
    case Scan =>
      cleanup()
      scan()
  }

  def handleData: Receive = {
    case Udp.Received(data, remote) =>
      DiscoveryMessage.deserialize(data) match {
        case Success(message: DiscoveryMessage.Request) =>
          updateStatus(message.sourceId)
          handleRequest(message)
        case Success(message: DiscoveryMessage.Response) =>
          verify(message.callId, remote) { peer =>
            updateStatus(peer.id)
            handleResponse(message, peer)
          }
        case Failure(error) =>
          // TODO: handler error properly
          log.info(
            s"${config.peerId} - Received corrupted UDP data from $remote (${data.size} bytes): ${error.getMessage}")
      }
  }

  def handleRequest(message: DiscoveryMessage.Request): Unit =
    message match {
      case Ping(callId, source) =>
        log.debug(s"Ping received from $source")
        send(source.socketAddress, Pong(callId))
        tryPing(source)

      case msg @ FindNode(callId, sourceId, targetId) =>
        log.debug(s"FindNode received from $sourceId")
        getPeer(sourceId) match {
          case Some(source) =>
            val neighbors = getNeighbors(targetId)
            send(source.socketAddress, Neighbors(callId, neighbors))
          case None =>
            pendingFindNode(msg)
        }
    }

  def handleResponse(message: DiscoveryMessage.Response, source: PeerInfo): Unit =
    message match {
      case Pong(_) =>
        log.debug(s"Pong received from $source")
        handlePong(source)

      case Neighbors(_, peers) =>
        log.debug(s"Receive neighbors from $source")
        peers.foreach(_.foreach(peer => tryPing(peer)))
    }
}
