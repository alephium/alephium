package org.alephium.flow.network

import java.net.InetSocketAddress

import scala.collection.mutable

import akka.actor.{ActorRef, Props, Timers}
import akka.io.{IO, Udp}
import org.alephium.util.{AVector, BaseActor}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model.{GroupIndex, PeerId, PeerInfo}

import scala.util.{Failure, Success}

object DiscoveryServer {
  case class PeerStatus(info: PeerInfo, createdAt: Long, updatedAt: Long)
  object PeerStatus {
    def fromInfo(info: PeerInfo): PeerStatus = {
      val now = System.currentTimeMillis
      PeerStatus(info, now, now)
    }
  }

  sealed trait Command

  case object GetPeers extends Command
  case object Scan     extends Command

  sealed trait Event

  case class Peers(infos: AVector[AVector[PeerStatus]]) extends Event

  def props(bootstrapPeers: AVector[PeerInfo])(implicit config: DiscoveryConfig): Props =
    Props(new DiscoveryServer(bootstrapPeers))
}

// TODO Add rate limiter to prevent "Ping/FindNode" flooding?
class DiscoveryServer(bootstrapPeers: AVector[PeerInfo])(implicit config: DiscoveryConfig)
    extends BaseActor
    with Timers {
  import DiscoveryServer._
  import DiscoveryMessage._

  import context.system

  implicit val orderingPeerId: Ordering[PeerId] = PeerId.ordering(config.peerId)

  val calls: mutable.Map[CallId, PeerInfo] = mutable.Map()

  val peersMax = config.peersPerGroup * config.groups
  val peers: AVector[mutable.SortedMap[PeerId, PeerStatus]] =
    AVector.tabulate(config.groups)(_ => mutable.SortedMap())

  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(config.udpPort))

  override def receive: Receive = binding

  def call(socket: ActorRef, address: PeerInfo)(f: CallId => DiscoveryMessage): Unit = {
    val callId = CallId.generate
    calls.put(callId, address)
    send(socket, address.socketAddress, f(callId))
  }

  def callPing(socket: ActorRef, address: PeerInfo): Unit = {
    call(socket, address)(Ping(_, config.nodeInfo))
  }

  def cleanup(now: Long): Unit = {
    (0 until config.groups).foreach { g =>
      val deadPeers = peers(g).values
        .filter(info => (now - info.updatedAt) > config.peersTimeout.toMillis)
        .map(_.info.id)
        .toSet

      peers(g) --= deadPeers
      calls --= calls.collect { case (id, addr) if deadPeers(addr.id) => id }
    }
  }

  def discovery(socket: ActorRef, addrs: AVector[AVector[PeerInfo]]): Unit = {
    val discovereds: AVector[PeerInfo] = addrs.flatMapWithIndex { (xs, i) =>
      val ps    = peers(i)
      val slots = config.peersPerGroup - ps.size

      if (slots > 0) {
        val unknowns = xs.filterNot { addr =>
          ps.contains(addr.id)
        }
        unknowns.sortBy(addr => PeerId.distance(config.peerId, addr.id)).takeUpto(slots)
      } else AVector.empty[PeerInfo]
    }

    discovereds.foreach(addr => callPing(socket, addr))
  }

  def discoveryAdd(peerId: PeerId, addr: PeerInfo): Unit = {
    val ps = peers(addr.group.value)
    if (ps.size < config.peersPerGroup) {
      log.debug(s"Discovered new peer $addr")
      val _ = ps.put(peerId, PeerStatus.fromInfo(addr))
    }
  }

  def send(socket: ActorRef, remote: InetSocketAddress, msg: DiscoveryMessage): Unit = {
    val bs = DiscoveryMessage.serialize(msg)
    socket ! Udp.Send(bs, remote)
  }

  def verified(remote: InetSocketAddress, callId: CallId)(f: (PeerId, GroupIndex) => Unit): Unit =
    calls
      .get(callId)
      .fold(log.warning(s"Received unrequested message from ${remote}")) { info =>
        if (remote != info.socketAddress) {
          log.warning(s"Unauthorized call answer from $remote")
        } else {
          calls.remove(callId)
          f(info.id, info.group)
        }
      }

  def handleCommand(socket: ActorRef, cmd: Command): Unit = {
    cmd match {
      case GetPeers => handleGetPeers()
      case Scan =>
        val now = System.currentTimeMillis

        cleanup(now)

        val actives = peers
          .flatMap(xs => AVector.from(xs.values))
          .sortBy(_.createdAt)
          .takeUpto(config.scanMax)
          .map(_.info)

        val bootstraps = if (actives.length < math.min(bootstrapPeers.length, config.scanMax)) {
          bootstrapPeers
            .filterNot(actives.contains)
            .takeUpto(config.scanMax - actives.length)
        } else AVector.empty[PeerInfo]

        (actives ++ bootstraps).foreach(addr => callPing(socket, addr))
    }
  }

  def handleGetPeers(): Unit = {
    sender() ! Peers(peers.map(xs => AVector.from(xs.values)))
  }

  def handleMessage(socket: ActorRef, remote: InetSocketAddress, msg: DiscoveryMessage): Unit = {
    msg match {
      case Ping(callId, peer) =>
        send(socket, remote, Pong(callId))
        discoveryAdd(peer.id, PeerInfo(peer.id, remote))

      case Pong(callId) =>
        verified(remote, callId) {
          case (peerId, group) =>
            handlePong(socket, remote, peerId, group)
        }

      case FindNode(callId, _, targetId) =>
        val nearests = peers.map { xs =>
          AVector
            .from(xs.values)
            .sortBy(p => PeerId.distance(targetId, p.info.id))
            .takeUpto(config.peersPerGroup)
            .map(_.info)
        }

        send(socket, remote, Neighbors(callId, nearests))

      case Neighbors(callId, nearests) =>
        verified(remote, callId) {
          case _ => discovery(socket, nearests)
        }
    }
  }

  def handlePong(socket: ActorRef,
                 remote: InetSocketAddress,
                 peerId: PeerId,
                 group: GroupIndex): Unit = {
    val addr = PeerInfo(peerId, remote)

    val ps = peers(group.value)

    ps.get(peerId)
      .fold {
        discoveryAdd(peerId, addr)
      } { info =>
        ps.update(addr.id, info.copy(updatedAt = System.currentTimeMillis))
      }

    if (peers.sumBy(_.size) < peersMax) {
      call(socket, addr)(FindNode(_, config.peerId, config.peerId))
    }
  }

  def binding: Receive = {
    case GetPeers =>
      handleGetPeers()
    case Udp.Bound(_) =>
      timers.startPeriodicTimer(Scan, Scan, config.scanFrequency)
      context.become(ready(sender()))

    case Udp.CommandFailed(bind: Udp.Bind) =>
      log.error(s"Could not bind the UDP socket ($bind)")
      context stop self
  }

  def ready(socket: ActorRef): Receive = {
    case cmd: Command =>
      handleCommand(socket, cmd)
    case Udp.Received(data, remote) =>
      DiscoveryMessage.deserialize(data) match {
        case Success(message) =>
          handleMessage(socket, remote, message)
        case Failure(error) =>
          log.info(
            s"${config.peerId} - Received corrupted UDP data from $remote (${data.size} bytes): ${error.getMessage}")
      }
  }
}
