package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import akka.io.Udp
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage._
import org.alephium.protocol.model.{GroupIndex, PeerId, PeerInfo}
import org.alephium.util.AVector

import scala.collection.mutable

trait DiscoveryServerState {
  implicit def config: DiscoveryConfig
  def log: LoggingAdapter

  def bootstrap: AVector[AVector[PeerInfo]]

  import DiscoveryServer._

  private var socket: ActorRef = _
  private val table            = AVector.fill(config.groups)(mutable.HashMap.empty[PeerId, PeerStatus])
  private val pendings         = mutable.HashMap.empty[PeerId, AwaitPong]
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
    table.mapWithIndex { (bucket, i) =>
      val peers = if (config.nodeInfo.group == GroupIndex(i)) {
        AVector.from(bucket.values.map(_.info)) :+ config.nodeInfo
      } else AVector.from(bucket.values.map(_.info))

      peers
        .filter(_.id != target)
        .sortBy(peer => target.hammingDist(peer.id))
        .takeUpto(config.neighborsPerGroup)
    }
  }

  def getBucket(peerId: PeerId): mutable.HashMap[PeerId, PeerStatus] = {
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

  def getPeer(peerId: PeerId): Option[PeerInfo] = {
    getBucket(peerId).get(peerId).map(_.info)
  }

  def getPending(peerId: PeerId): Option[AwaitPong] = {
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

  def getPendingStatus(peerId: PeerId): Option[AwaitPong] = {
    pendings.get(peerId)
  }

  def cleanup(): Unit = {
    val now = System.currentTimeMillis()
    table.foreach { bucket =>
      val toRemove = bucket.values
        .filter(status => now - status.updateAt > config.peersTimeout.toMillis)
        .map(_.info.id)
        .toSet
      bucket --= toRemove
    }

    val deadPendings = pendings.collect {
      case (peerId, status) if now - status.pingAt > config.peersTimeout.toMillis => peerId
    }
    pendings --= deadPendings
  }

  private def appendPeer(peer: PeerInfo, bucket: mutable.HashMap[PeerId, PeerStatus]): Unit = {
    log.debug(s"Add a new peer $peer")
    bucket += peer.id -> PeerStatus.fromInfo(peer)
    fetchNeighbors(peer)
  }

  def scan(): Unit = {
    table.foreachWithIndex { (bucket, i) =>
      val sortedNeighbors =
        AVector.from(bucket.values).sortBy(status => config.nodeId.hammingDist(status.info.id))
      sortedNeighbors
        .takeUpto(config.scanMaxPerGroup)
        .foreach(status => fetchNeighbors(status.info))
      val bootstrapNum = config.scanMaxPerGroup - sortedNeighbors.length
      if (bootstrapNum > 0) {
        bootstrap(i).takeUpto(bootstrapNum).foreach(fetchNeighbors)
      }
    }
  }

  def fetchNeighbors(peer: PeerInfo): Unit = {
    send(peer.socketAddress, FindNode(config.nodeId))
  }

  def send(remote: InetSocketAddress, payload: Payload): Unit = {
    val message = DiscoveryMessage.from(payload)
    socket ! Udp.Send(DiscoveryMessage.serialize(message), remote)
  }

  def tryPing(peer: PeerInfo): Unit = {
    if (isUnknown(peer.id) && isPendingAvailable) {
      log.info(s"Ping $peer")
      send(peer.socketAddress, Ping(config.nodeInfo.socketAddress))
      pendings += (peer.id -> AwaitPong(peer.socketAddress, System.currentTimeMillis()))
    }
  }

  def handlePong(peerId: PeerId): Unit = {
    pendings.get(peerId) match {
      case Some(AwaitPong(remote, _)) =>
        pendings.remove(peerId)
        val peer   = PeerInfo(peerId, remote)
        val bucket = getBucket(peerId)
        if (bucket.size < config.neighborsPerGroup) {
          appendPeer(peer, bucket)
        } else {
          val myself   = config.nodeId
          val furthest = bucket.keys.minBy(myself.hammingDist)
          if (myself.hammingDist(peerId) < myself.hammingDist(furthest)) {
            bucket -= furthest
            appendPeer(peer, bucket)
          }
        }
      case None =>
        log.debug(s"Uninvited pong message received")
    }
  }
}
