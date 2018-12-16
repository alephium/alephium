package org.alephium.flow.network

import java.net.InetSocketAddress
import scala.collection.mutable

import akka.actor.{ActorRef, Props, Timers}
import akka.io.{IO, Udp}
import org.alephium.util.BaseActor

import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model.{PeerAddress, PeerId}

object DiscoveryServer {
  case class PeerInfo(address: PeerAddress, createdAt: Long, updatedAt: Long)
  object PeerInfo {
    def fromAddress(address: PeerAddress): PeerInfo = {
      val now = System.currentTimeMillis
      PeerInfo(address, now, now)
    }
  }

  sealed trait Command

  case class Bootstrap(addresses: Set[PeerAddress]) extends Command
  case object Scan                                  extends Command

  sealed trait Event

  case class Discovery(peers: Set[PeerInfo]) extends Event

  def props(config: DiscoveryConfig, subscribers: Set[ActorRef]): Props =
    Props(new DiscoveryServer(config, subscribers))
}

// TODO Add rate limiter to prevent "Ping/FindNode" flooding?
class DiscoveryServer(config: DiscoveryConfig, subscribers: Set[ActorRef])
    extends BaseActor
    with Timers {
  import DiscoveryServer._
  import DiscoveryMessage._

  import context.system

  implicit val orderingPeerId: Ordering[PeerId] = PeerId.ordering(config.peerId)

  val calls: mutable.Map[CallId, PeerAddress]    = mutable.Map()
  val peers: mutable.SortedMap[PeerId, PeerInfo] = mutable.SortedMap()

  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(config.udpPort))

  override def receive: Receive = binding

  def call(socket: ActorRef, address: PeerAddress)(f: CallId => DiscoveryMessage): Unit = {
    val callId = CallId.generate
    calls.put(callId, address)
    send(socket, address.socketAddress, f(callId))
  }

  def callPing(socket: ActorRef, address: PeerAddress): Unit = {
    call(socket, address)(Ping(_, config.peerId))
  }

  def cleanup(now: Long): Unit = {
    val deadPeers = peers.values
      .filter(info => (now - info.updatedAt) > config.peersTimeout.toMillis)
      .map(_.address.id)
      .toSet

    peers --= deadPeers
    calls --= calls.collect { case (id, addr) if deadPeers(addr.id) => id }
  }

  def send(socket: ActorRef, remote: InetSocketAddress, msg: DiscoveryMessage): Unit = {
    val bs = DiscoveryMessage.serializer.serialize(msg)
    socket ! Udp.Send(bs, remote)
  }

  def verified(remote: InetSocketAddress, callId: CallId)(f: PeerId => Unit): Unit =
    calls
      .get(callId)
      .fold(log.warning(s"Received unrequested message from ${remote}")) { address =>
        if (remote != address.socketAddress) {
          log.warning(s"Unauthorized call answer from $remote")
        } else {
          calls.remove(callId)
          f(address.id)
        }
      }

  def handleBootstrap(addresses: Set[PeerAddress]): Unit = {
    peers ++= addresses.map(addr => (addr.id, PeerInfo.fromAddress(addr)))
  }

  def handleCommand(socket: ActorRef, cmd: Command): Unit = {
    cmd match {
      case Bootstrap(addresses) =>
        handleBootstrap(addresses)

      case Scan =>
        val now = System.currentTimeMillis

        cleanup(now)

        peers.values.take(config.scanMax).foreach(info => callPing(socket, info.address))
    }
  }

  def handleMessage(socket: ActorRef, remote: InetSocketAddress, msg: DiscoveryMessage): Unit = {
    msg match {
      case Ping(callId, peerId) =>
        send(socket, remote, Pong(callId, peerId, config.peerId))

        if (!peers.contains(peerId) && (peers.size < config.peersMax)) {
          callPing(socket, PeerAddress(peerId, remote))
        }

      case Pong(callId, thisPeerId, remotePeerId) =>
        verified(remote, callId) { peerId =>
          if (config.peerId != thisPeerId || peerId != remotePeerId) {
            log.warning(s"Unauthorized Pong from $remote")
          } else {
            handlePong(socket, remote, peerId)
          }
        }

      case FindNode(callId, target) =>
        val nearests = peers.values.toSeq
          .sortBy(p => PeerId.distance(target, p.address.id))
          .take(config.neighborsMax)
          .map(_.address)

        send(socket, remote, Neighbors(callId, nearests))

      case Neighbors(callId: CallId, nearests: Seq[PeerAddress]) =>
        verified(remote, callId) { _ =>
          val neighbors = nearests
            .sortBy(p => PeerId.distance(p.id, config.peerId))
            .filterNot(_.id == config.peerId)
            .take(config.peersMax - peers.size)

          neighbors.foreach(callPing(socket, _))
        }
    }
  }

  def handlePong(socket: ActorRef, remote: InetSocketAddress, peerId: PeerId): Unit = {
    val now         = System.currentTimeMillis
    val peerAddress = PeerAddress(peerId, remote)

    peers
      .get(peerId)
      .fold {
        peers.put(peerId, PeerInfo(peerAddress, now, now))
        log.debug(s"Discovered new peer $peerAddress")

        if (peers.size > config.peersMax) {
          val earliests = peers.values.toVector
            .sortBy(_.createdAt)
            .drop(config.peersMax)
            .map(_.address.id)

          earliests.foreach(peers.remove(_))
        }

        subscribers.foreach(_ ! Discovery(peers.values.toSet))
      } { info =>
        peers.update(peerAddress.id, info.copy(updatedAt = now))
      }

    if (peers.size < config.peersMax) {
      call(socket, peerAddress)(FindNode(_, config.peerId))
    }
  }

  def binding: Receive = {
    case Udp.Bound(_) =>
      timers.startPeriodicTimer(Scan, Scan, config.scanFrequency)
      context.become(ready(sender()))

    case Udp.CommandFailed(bind: Udp.Bind) =>
      log.error(s"Could not bind the UDP socket ($bind)")
      context stop self

    case Bootstrap(addresses) =>
      handleBootstrap(addresses)
  }

  def ready(socket: ActorRef): Receive = {
    case cmd: Command =>
      handleCommand(socket, cmd)
    case Udp.Received(data, remote) =>
      DiscoveryMessage.deserializer
        .deserialize(data)
        .fold(
          err =>
            log.info(
              s"${config.peerId} - Received corrupted UDP data from $remote (${data.size} bytes)"),
          handleMessage(socket, remote, _))
  }

}
