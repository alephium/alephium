package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import akka.io.Udp
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage.{CallId, FindNode, Neighbors, Ping}
import org.alephium.protocol.model.{PeerId, PeerInfo}
import org.alephium.util.AVector

import scala.collection.mutable

trait DiscoveryServerState {
  implicit def config: DiscoveryConfig
  def log: LoggingAdapter

  import AnotherDiscoveryServer._

  implicit val orderingPeerId: Ordering[PeerId] = PeerId.hammingOrder(config.peerId)

  private var socket: ActorRef = _
  private val calls            = mutable.HashMap.empty[CallId, PeerInfo]
  private val table            = AVector.fill(config.groups)(mutable.SortedMap.empty[PeerId, PeerStatus])
  private val pendings         = mutable.HashMap.empty[PeerId, PendingStatus]
  private val pendingMax       = 2 * config.groups * config.neighborsPerGroup

  def setSocket(s: ActorRef): Unit = {
    socket = s
  }

  def getActivePeers: AVector[AVector[PeerInfo]] = {
    table.map { bucket =>
      AVector.from(bucket.values.map(_.info))
    }
  }

  def getNeighbors(target: PeerId): AVector[AVector[PeerInfo]] = {
    table.map { bucket =>
      AVector
        .from(bucket.keys)
        .sortBy(target.hammingDist)
        .takeUpto(config.neighborsPerGroup)
        .map(bucket(_).info)
    }
  }

  def getBucket(peerId: PeerId): mutable.SortedMap[PeerId, PeerStatus] = {
    val peerGroup = peerId.groupIndex
    table(peerGroup.value)
  }

  def isInTable(peerId: PeerId): Boolean = {
    getBucket(peerId).contains(peerId)
  }

  def isPending(peerId: PeerId): Boolean = {
    pendings.contains(peerId)
  }

  def isUnknown(peerId: PeerId): Boolean = !isInTable(peerId) && !isPending(peerId)

  def isPendingAvailable: Boolean = pendings.size < pendingMax

  def getCall(callId: CallId): Option[PeerInfo] = {
    calls.get(callId)
  }

  def getPeer(peerId: PeerId): Option[PeerInfo] = {
    getBucket(peerId).get(peerId).map(_.info)
  }

  def getPending(peerId: PeerId): Option[PendingStatus] = {
    pendings.get(peerId)
  }

  def updateStatus(peerId: PeerId): Unit = {
    val bucket = getBucket(peerId)
    bucket.get(peerId) match {
      case Some(status) =>
        bucket(peerId) = status.copy(updateAt = System.currentTimeMillis())
      case None => ()
    }
  }

  def getPendingStatus(peerId: PeerId): Option[PendingStatus] = {
    pendings.get(peerId)
  }

  def setPendingStatus(peerId: PeerId, status: PendingStatus): Unit = {
    if (isPendingAvailable || isPending(peerId)) {
      pendings(peerId) = status
    }
  }

  def cleanup(): Unit = {
    val now = System.currentTimeMillis()
    table.foreach { bucket =>
      val toRemove = bucket.values
        .filter(status => now - status.updateAt > config.peersTimeout.toMillis)
        .map(_.info.id)
        .toSet
      bucket --= toRemove
      calls --= calls.collect { case (callId, peer) if toRemove.contains(peer.id) => callId }
    }

    val deadPendings = pendings.collect {
      case (peerId, status) if now - status.pingAt > config.peersTimeout.toMillis => peerId
    }
    pendings --= deadPendings
  }

  def appendPeer(peer: PeerInfo, bucket: mutable.SortedMap[PeerId, PeerStatus]): Unit = {
    bucket += peer.id -> PeerStatus.fromInfo(peer)
    fetchNeighbors(peer)
  }

  def scan(): Unit = {
    table.foreach { bucket =>
      bucket.take(config.scanMax).values.foreach(status => fetchNeighbors(status.info))
    }
  }

  def fetchNeighbors(peer: PeerInfo): Unit = {
    call(peer)(FindNode(_, config.peerId, config.peerId))
  }

  def send(remote: InetSocketAddress, message: DiscoveryMessage): Unit = {
    socket ! Udp.Send(DiscoveryMessage.serialize(message), remote)
  }

  def call(peer: PeerInfo)(f: CallId => DiscoveryMessage): Unit = {
    val callId = CallId.generate
    calls.put(callId, peer)
    send(peer.socketAddress, f(callId))
  }

  def verify(callId: CallId, remote: InetSocketAddress)(f: PeerInfo => Unit): Unit = {
    calls.get(callId) match {
      case Some(peer) =>
        calls.remove(callId)
        f(peer)
      case None =>
        log.warning(s"Received unauthorized message $remote")
    }
  }

  def tryPing(peer: PeerInfo): Unit = {
    if (isUnknown(peer.id) && isPendingAvailable) {
      log.info(s"Ping $peer")
      call(peer)(Ping(_, config.nodeInfo))
      pendings += (peer.id -> AwaitPong(System.currentTimeMillis()))
    }
  }

  def handlePong(peer: PeerInfo): Unit = {
    assert(isPending(peer.id))
    pendings(peer.id) match {
      case FindNodeReceived(message, _) =>
        val neighbors = getNeighbors(message.targetId)
        send(peer.socketAddress, Neighbors(message.callId, neighbors))
      case AwaitPong(_) =>
        log.debug(s"Pong received right after ping")
    }
    pendings.remove(peer.id)

    val bucket = getBucket(peer.id)
    if (bucket.size < config.neighborsPerGroup) {
      appendPeer(peer, bucket)
    } else {
      val myself   = config.peerId
      val furthest = bucket.lastKey
      if (myself.hammingDist(peer.id) < myself.hammingDist(furthest)) {
        bucket -= furthest
        appendPeer(peer, bucket)
      }
    }
  }

  def pendingFindNode(message: FindNode): Unit = {
    val sourceId = message.sourceId
    getPendingStatus(sourceId) match {
      case Some(status: AwaitPong) =>
        setPendingStatus(sourceId, FindNodeReceived(message, status.pingAt))
      case _ =>
        log.warning(s"Received invalid find_node message from $sourceId")
    }
  }
}
