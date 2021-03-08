// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.network

import java.net.InetSocketAddress

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

import akka.event.LoggingAdapter
import akka.io.Udp

import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig, NetworkConfig}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage._
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo, PeerId}
import org.alephium.util.{ActorRefT, AVector, TimeStamp}

// scalastyle:off number.of.methods
trait DiscoveryServerState {
  implicit def brokerConfig: BrokerConfig
  implicit def discoveryConfig: DiscoveryConfig
  implicit def networkConfig: NetworkConfig
  def log: LoggingAdapter
  def bootstrap: ArraySeq[InetSocketAddress]
  def selfCliqueInfo: CliqueInfo
  def selfCliqueId: CliqueId = selfCliqueInfo.id

  lazy val selfPeerId: PeerId                                    = selfCliqueInfo.selfInterBrokerInfo.peerId
  lazy val selfPeerInfoOpt: Option[BrokerInfo]                   = selfCliqueInfo.selfBrokerInfo
  lazy val selfCliqueBrokerInfosOpt: Option[AVector[BrokerInfo]] = selfCliqueInfo.interBrokers

  import DiscoveryServer._

  private var socket: ActorRefT[Udp.Command] = _

  protected val table    = mutable.HashMap.empty[PeerId, PeerStatus]
  private val pendings   = mutable.HashMap.empty[PeerId, AwaitPong]
  private val pendingMax = 20 * brokerConfig.groups * discoveryConfig.neighborsPerGroup

  private val neighborMax = discoveryConfig.neighborsPerGroup * brokerConfig.groups

  def setSocket(s: ActorRefT[Udp.Command]): Unit = {
    socket = s
  }

  def getActivePeers: AVector[BrokerInfo] = {
    AVector.from(table.values.map(_.info))
  }

  def getPeersNum: Int = table.size

  def getPeersWeight: Int = {
    table.values.foldLeft(0)(_ + _.info.groupNumPerBroker)
  }

  def getNeighbors(target: CliqueId): AVector[BrokerInfo] = {
    val candidates = AVector.from(table.values.map(_.info).filter(_.cliqueId != target))
    candidates
      .sortBy(info => target.hammingDist(info.cliqueId))
      .takeUpto(neighborMax)
  }

  def isInTable(peerId: PeerId): Boolean = {
    table.contains(peerId)
  }

  def isPending(peerId: PeerId): Boolean = {
    pendings.contains(peerId)
  }

  def isUnknown(peerId: PeerId): Boolean = !isInTable(peerId) && !isPending(peerId)

  def isPendingAvailable: Boolean = pendings.size < pendingMax

  def getPeer(peerId: PeerId): Option[BrokerInfo] = {
    table.get(peerId).map(_.info)
  }

  def getPending(peerId: PeerId): Option[AwaitPong] = {
    pendings.get(peerId)
  }

  def updateStatus(peerId: PeerId): Unit = {
    table.get(peerId) match {
      case Some(status) =>
        table(peerId) = status.copy(updateAt = TimeStamp.now())
      case None => ()
    }
  }

  def getPendingStatus(peerId: PeerId): Option[AwaitPong] = {
    pendings.get(peerId)
  }

  def banPeerFromAddress(address: InetSocketAddress): Boolean = {
    val bannedPeer = table.values
      .filter(status => status.info.address == address)
      .map(_.info.peerId)

    bannedPeer.foreach(banPeer)

    bannedPeer.nonEmpty
  }

  def banPeer(peerId: PeerId): Unit = {
    table -= peerId
    pendings -= peerId
  }

  def cleanup(): Unit = {
    val now = TimeStamp.now()
    val expired = table.values
      .filter(status => (now -- status.updateAt).exists(_ >= discoveryConfig.expireDuration))
      .map(_.info.peerId)
      .toSet
    table --= expired

    val deadPendings = pendings.collect {
      case (cliqueId, status) if (now -- status.pingAt).exists(_ >= discoveryConfig.peersTimeout) =>
        cliqueId
    }
    pendings --= deadPendings
  }

  private def appendPeer(peerInfo: BrokerInfo): Unit = {
    log.debug(s"Adding a new peer: $peerInfo")
    table += peerInfo.peerId -> PeerStatus.fromInfo(peerInfo)
    fetchNeighbors(peerInfo)
    publishNewPeer(peerInfo)
  }

  protected def addSelfCliquePeer(peerInfo: BrokerInfo): Unit = {
    table += peerInfo.peerId -> PeerStatus.fromInfo(peerInfo)
  }

  def publishNewPeer(peerInfo: BrokerInfo): Unit

  // TODO: improve scan algorithm
  def scan(): Unit = {
    table.values
      .foreach(status => if (status.info.peerId != selfPeerId) { tryPing(status.info) })
    val emptySlotNum = neighborMax - getPeersWeight
    val bootstrapNum = if (emptySlotNum > 0) emptySlotNum else 0
    bootstrap.take(bootstrapNum).foreach(ping)
  }

  def shouldScanFast(): Boolean = {
    table.isEmpty
  }

  def fetchNeighbors(info: BrokerInfo): Unit = {
    fetchNeighbors(info.address)
  }

  def fetchNeighbors(remote: InetSocketAddress): Unit = {
    send(remote, FindNode(selfCliqueId))
  }

  def send(remote: InetSocketAddress, payload: Payload): Unit = {
    val message = DiscoveryMessage.from(selfCliqueId, payload)
    socket ! Udp.Send(DiscoveryMessage.serialize(message, networkConfig.networkType), remote)
  }

  def tryPing(peerInfo: BrokerInfo): Unit = {
    if (isUnknown(peerInfo.peerId) && isPendingAvailable) {
      ping(peerInfo)
    }
  }

  def ping(peerInfo: BrokerInfo): Unit = {
    log.info(s"Sending Ping to $peerInfo")
    val remoteAddress = peerInfo.address
    send(remoteAddress, Ping(selfPeerInfoOpt))
    pendings += (peerInfo.peerId -> AwaitPong(remoteAddress, TimeStamp.now()))
  }

  def ping(remote: InetSocketAddress): Unit = {
    log.debug(s"Sending Ping to $remote")
    send(remote, Ping(selfPeerInfoOpt))
  }

  def handlePong(peerInfo: BrokerInfo): Unit = {
    val peerId = peerInfo.peerId
    pendings.get(peerId) match {
      case Some(AwaitPong(_, _)) =>
        pendings.remove(peerId)
        if (getPeersWeight < neighborMax) {
          appendPeer(peerInfo)
        } else {
          tryInsert(peerInfo)
        }
        updateStatus(peerId)
        fetchNeighbors(peerInfo)
      case None =>
        tryPing(peerInfo)
    }
  }

  def remove(peer: InetSocketAddress): Unit = {
    val peersToRemove = table.values.filter(_.info.address == peer).map(_.info.peerId)
    table --= peersToRemove
    pendings --= peersToRemove
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  private def tryInsert(peerInfo: BrokerInfo): Unit = {
    val myself   = selfCliqueId
    val furthest = table.keys.maxBy(peerId => myself.hammingDist(peerId.cliqueId))
    if (myself.hammingDist(peerInfo.cliqueId) < myself.hammingDist(furthest.cliqueId)) {
      table -= furthest
      appendPeer(peerInfo)
    }
  }
}
