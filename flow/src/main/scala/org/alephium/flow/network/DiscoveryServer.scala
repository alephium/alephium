package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.{IO, Udp}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage._
import org.alephium.protocol.model.{PeerId, PeerInfo}
import org.alephium.util.{AVector, BaseActor}

import scala.util.{Failure, Success}

object DiscoveryServer {
  def props(bootstrap: AVector[InetSocketAddress])(implicit config: DiscoveryConfig): Props =
    Props(new DiscoveryServer(bootstrap))

  def props(peers: InetSocketAddress*)(implicit config: DiscoveryConfig): Props = {
    props(AVector.from(peers))
  }

  case class PeerStatus(info: PeerInfo, updateAt: Long)
  object PeerStatus {
    def fromInfo(info: PeerInfo): PeerStatus = {
      val now = System.currentTimeMillis
      PeerStatus(info, now)
    }
  }

  case class AwaitPong(remote: InetSocketAddress, pingAt: Long)

  sealed trait Command
  case object GetPeers               extends Command
  case class Disable(peerId: PeerId) extends Command
  case object Scan                   extends Command

  sealed trait Event
  case class Peers(peers: AVector[AVector[PeerInfo]]) extends Event
}

/*
 * This variant of Kademlia protocol follows these rules:
 *  -> send ping to another peer to detect the liveness
 *  -> pong back when received valid ping
 *  -> ping back to verify peer address when received ping for the first time from another peer
 *  -> send find_node to discover peers
 *  -> send neighbors back when received find_node
 *  -> ping all the new neighbors received from peers
 *  -> send find_node periodically to peers to update knowledge of neighbors
 *
 *
 *  TODO: each group has several buckets instead of just one bucket
 */
class DiscoveryServer(val bootstrap: AVector[InetSocketAddress])(
    implicit val config: DiscoveryConfig)
    extends BaseActor
    with DiscoveryServerState {
  import DiscoveryServer._
  import context.system
  import context.dispatcher

  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(config.udpPort))

  def receive: Receive = binding orElse handleCommand

  def binding: Receive = {
    case Udp.Bound(_) =>
      log.debug(s"UDP server bound successfully")
      setSocket(sender())
      bootstrap.foreach(fetchNeighbors)
      system.scheduler.schedule(config.scanFrequency, config.scanFrequency, self, Scan)
      context.become(ready)

    case Udp.CommandFailed(bind: Udp.Bind) =>
      log.error(s"Could not bind the UDP socket ($bind)")
      context stop self
  }

  def ready: Receive = handleData orElse handleCommand

  def handleData: Receive = {
    case Udp.Received(data, remote) =>
      DiscoveryMessage.deserialize(data) match {
        case Success(message: DiscoveryMessage) =>
          val sourceId = PeerId.fromPublicKey(message.header.publicKey)
          updateStatus(sourceId)
          handlePayload(sourceId, remote)(message.payload)
        case Failure(error) =>
          // TODO: handler error properly
          log.info(
            s"${config.nodeId} - Received corrupted UDP data from $remote (${data.size} bytes): ${error.getMessage}")
      }
  }

  def handleCommand: Receive = {
    case Scan =>
      log.debug("Scan the available peers")
      cleanup()
      scan()
    case GetPeers =>
      sender() ! Peers(getActivePeers)
    case Disable(peerId) =>
      getBucket(peerId) -= peerId
      ()
  }

  def handlePayload(sourceId: PeerId, remote: InetSocketAddress)(payload: Payload): Unit =
    payload match {
      case Ping(sourceAddress) =>
        send(sourceAddress, Pong())
        tryPing(PeerInfo(sourceId, sourceAddress))
      case Pong() =>
        handlePong(sourceId)
      case FindNode(targetId) =>
        val sourceAddress = getPeer(sourceId) match {
          case Some(source) => source.socketAddress
          case None         => remote
        }
        val neighbors = getNeighbors(targetId)
        send(sourceAddress, Neighbors(neighbors))
      case Neighbors(peers) =>
        peers.foreach(_.foreach(tryPing))
    }
}
