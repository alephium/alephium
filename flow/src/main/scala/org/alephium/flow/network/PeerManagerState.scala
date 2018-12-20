package org.alephium.flow.network

import akka.actor.ActorRef
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.PeerManager.PeerInfo
import org.alephium.protocol.model.{GroupIndex, PeerId}
import org.alephium.util.AVector

import scala.collection.mutable

trait PeerManagerState {
  implicit def config: PlatformConfig

  val peers: AVector[mutable.HashMap[PeerId, PeerInfo]] =
    AVector.fill(config.groups)(mutable.HashMap.empty)

  def peersSize: Int = peers.sumBy(_.size)

  private def getTablePerGroup(peerId: PeerId): mutable.HashMap[PeerId, PeerInfo] = {
    getTablePerGroup(peerId.groupIndex)
  }

  private def getTablePerGroup(groupIndex: GroupIndex): mutable.HashMap[PeerId, PeerInfo] = {
    peers(groupIndex.value)
  }

  def contains(peerId: PeerId): Boolean = {
    val table = getTablePerGroup(peerId)
    table.contains(peerId)
  }

  def addPeer(peer: PeerInfo): Unit = {
    val table = getTablePerGroup(peer.id)
    table += peer.id -> peer
  }

  def removePeer(peerId: PeerId): Unit = {
    val table = getTablePerGroup(peerId)
    table -= peerId
  }

  def getPeerInfo(peerId: PeerId): Option[PeerInfo] = {
    val table = getTablePerGroup(peerId)
    table.get(peerId)
  }

  def getHandler(peerId: PeerId): Option[ActorRef] = {
    getPeerInfo(peerId).map(_.tcpHandler)
  }

  def traversePeers[T](f: PeerInfo => T): Unit = {
    peers.foreach(_.values.foreach(f))
  }

  def traverseGroup[T](groupIndex: GroupIndex, f: PeerInfo => T): Unit = {
    getTablePerGroup(groupIndex).values.foreach(f)
  }

  def getPeers: AVector[AVector[PeerInfo]] = {
    peers.map(table => AVector.from(table.values))
  }
}
