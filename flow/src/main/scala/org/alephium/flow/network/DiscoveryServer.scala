package org.alephium.flow.network

import java.net.InetSocketAddress

import scala.collection.mutable

import akka.actor.{ActorRef, Props, Timers}
import akka.io.{IO, Udp}
import org.alephium.util.{AVector, BaseActor}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model.{GroupIndex, PeerAddress, PeerId}

import scala.util.{Failure, Success}

object DiscoveryServer {
  case class PeerInfo(address: PeerAddress, createdAt: Long, updatedAt: Long)
  object PeerInfo {
    def fromAddress(address: PeerAddress): PeerInfo = {
      val now = System.currentTimeMillis
      PeerInfo(address, now, now)
    }
  }

  sealed trait Command

  case object GetPeers extends Command
  case object Scan     extends Command

  sealed trait Event

  case class Peers(infos: AVector[AVector[PeerInfo]]) extends Event

  def props(config: DiscoveryConfig, bootstrapPeers: AVector[PeerAddress]): Props =
    Props(new DiscoveryServer(config, bootstrapPeers))
}

// TODO Add rate limiter to prevent "Ping/FindNode" flooding?
class DiscoveryServer(config: DiscoveryConfig, bootstrapPeers: AVector[PeerAddress])
    extends BaseActor
    with Timers {
  import DiscoveryServer._
  import DiscoveryMessage._

  import context.system

  implicit val orderingPeerId: Ordering[PeerId] = PeerId.ordering(config.peerId)

  val calls: mutable.Map[CallId, PeerAddress] = mutable.Map()

  val peersMax = config.peersPerGroup * config.groups
  val peers: AVector[mutable.SortedMap[PeerId, PeerInfo]] =
    AVector.tabulate(config.groups)(_ => mutable.SortedMap())

  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(config.udpPort))

  override def receive: Receive = binding

  def call(socket: ActorRef, address: PeerAddress)(f: CallId => DiscoveryMessage): Unit = {
    val callId = CallId.generate
    calls.put(callId, address)
    send(socket, address.socketAddress, f(callId))
  }

  def callPing(socket: ActorRef, address: PeerAddress): Unit = {
    call(socket, address)(Ping(_, config.peerId, config.group))
  }

  def cleanup(now: Long): Unit = {
    (0 until config.groups).foreach { g =>
      val deadPeers = peers(g).values
        .filter(info => (now - info.updatedAt) > config.peersTimeout.toMillis)
        .map(_.address.peerId)
        .toSet

      peers(g) --= deadPeers
      calls --= calls.collect { case (id, addr) if deadPeers(addr.peerId) => id }
    }
  }

  def discovery(socket: ActorRef, addrs: AVector[AVector[PeerAddress]]): Unit = {
    val discovereds: AVector[PeerAddress] = addrs.flatMapWithIndex { (xs, i) =>
      val ps    = peers(i)
      val slots = config.peersPerGroup - ps.size

      if (slots > 0) {
        val unknowns = xs.filterNot { addr =>
          ps.contains(addr.peerId)
        }
        unknowns.sortBy(addr => PeerId.distance(config.peerId, addr.peerId)).takeUpto(slots)
      } else AVector.empty[PeerAddress]
    }

    discovereds.foreach(addr => callPing(socket, addr))
  }

  def discoveryAdd(peerId: PeerId, addr: PeerAddress): Unit = {
    val ps = peers(addr.group.value)
    if (ps.size < config.peersPerGroup) {
      log.debug(s"Discovered new peer $addr")
      val _ = ps.put(peerId, PeerInfo.fromAddress(addr))
    }
  }

  def send(socket: ActorRef, remote: InetSocketAddress, msg: DiscoveryMessage): Unit = {
    val bs = DiscoveryMessage.serializer.serialize(msg)
    socket ! Udp.Send(bs, remote)
  }

  def verified(remote: InetSocketAddress, callId: CallId)(f: (PeerId, GroupIndex) => Unit): Unit =
    calls
      .get(callId)
      .fold(log.warning(s"Received unrequested message from ${remote}")) { address =>
        if (remote != address.socketAddress) {
          log.warning(s"Unauthorized call answer from $remote")
        } else {
          calls.remove(callId)
          f(address.peerId, address.group)
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
          .map(_.address)

        val bootstraps = if (actives.length < math.min(bootstrapPeers.length, config.scanMax)) {
          bootstrapPeers
            .filterNot(actives.contains)
            .takeUpto(config.scanMax - actives.length)
        } else AVector.empty[PeerAddress]

        (actives ++ bootstraps).foreach(addr => callPing(socket, addr))
    }
  }

  def handleGetPeers(): Unit = {
    sender() ! Peers(peers.map(xs => AVector.from(xs.values)))
  }

  def handleMessage(socket: ActorRef, remote: InetSocketAddress, msg: DiscoveryMessage): Unit = {
    msg match {
      case Ping(callId, peerId, group) =>
        if (GroupIndex.validate(group, config.groups)) {
          send(socket, remote, Pong(callId, config.peerId, config.group))

          discoveryAdd(peerId, PeerAddress(peerId, group, remote))
        } else {
          log.warning(s"Unauthorized Ping (group=$group) from $remote")
        }

      case Pong(callId, remotePeerId, remoteGroupId) =>
        verified(remote, callId) {
          case (peerId, group) =>
            if (peerId != remotePeerId || group != remoteGroupId) {
              log.warning(s"Unauthorized Pong from $remote")
            } else {
              handlePong(socket, remote, peerId, group)
            }
        }

      case FindNode(callId, target) =>
        val nearests = peers.map { xs =>
          AVector
            .from(xs.values)
            .sortBy(p => PeerId.distance(target, p.address.peerId))
            .takeUpto(config.peersPerGroup)
            .map(_.address)
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
    val addr = PeerAddress(peerId, group, remote)

    val ps = peers(group.value)

    ps.get(peerId)
      .fold {
        discoveryAdd(peerId, addr)
      } { info =>
        ps.update(addr.peerId, info.copy(updatedAt = System.currentTimeMillis))
      }

    if (peers.sumBy(_.size) < peersMax) {
      call(socket, addr)(FindNode(_, config.peerId))
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
      DiscoveryMessage.deserializer.deserialize(data) match {
        case Success(message) =>
          if (DiscoveryMessage.validate(message)(config.peerId, config.groups)) {
            handleMessage(socket, remote, message)
          } else {
            log.info(s"${config.peerId} - Received non-valid UDP message from $remote")
          }
        case Failure(error) =>
          log.info(
            s"${config.peerId} - Received corrupted UDP data from $remote (${data.size} bytes): ${error.getMessage}")
      }
  }

}
