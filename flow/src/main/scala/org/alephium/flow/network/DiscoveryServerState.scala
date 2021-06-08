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

import java.net.{InetAddress, InetSocketAddress}

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

import akka.event.LoggingAdapter

import org.alephium.flow.network.udp.UdpServer
import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig, NetworkConfig}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage._
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo, GroupIndex, PeerId}
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
  lazy val tableInitialSize                                      = selfCliqueBrokerInfosOpt.map(_.length).getOrElse(0)

  import DiscoveryServer._

  private var socketOpt: Option[ActorRefT[UdpServer.Command]] = None

  protected val table      = mutable.HashMap.empty[PeerId, PeerStatus]
  private val pendings     = mutable.HashMap.empty[PeerId, AwaitPong]
  private val pendingMax   = 20 * brokerConfig.groups * discoveryConfig.neighborsPerGroup
  private val unreachables = mutable.HashMap.empty[InetSocketAddress, TimeStamp]

  private val neighborMax = discoveryConfig.neighborsPerGroup * brokerConfig.groups

  def setSocket(s: ActorRefT[UdpServer.Command]): Unit = {
    socketOpt = Some(s)
  }

  def unsetSocket(): Unit = {
    socketOpt = None
  }

  def getActivePeers: AVector[BrokerInfo] = {
    AVector
      .from(table.values.map(_.info))
      .filter(info => mightReachable(info.address))
      .sortBy(broker => selfCliqueId.hammingDist(broker.cliqueId))
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

  def banPeerFromAddress(address: InetAddress): Boolean = {
    val bannedPeer = table.values
      .filter(status => status.info.address.getAddress == address)
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
    val expired = table.values.view
      .filter(status => (now -- status.updateAt).exists(_ >= discoveryConfig.expireDuration))
      .filter(_.info.cliqueId != selfCliqueId)
      .map(_.info.peerId)
    table --= expired

    val deadPendings = pendings.collect {
      case (cliqueId, status) if (now -- status.pingAt).exists(_ >= discoveryConfig.peersTimeout) =>
        cliqueId
    }
    pendings --= deadPendings
  }

  def setUnreachable(remote: InetSocketAddress): Unit = {
    unreachables += remote -> TimeStamp.now().plusMinutesUnsafe(10)
    remove(remote)
  }

  def mightReachable(remote: InetSocketAddress): Boolean = {
    !unreachables.contains(remote)
  }

  def appendPeer(peerInfo: BrokerInfo): Unit = {
    log.info(s"Adding a new peer: $peerInfo")
    table += peerInfo.peerId -> PeerStatus.fromInfo(peerInfo)
    publishNewPeer(peerInfo)
  }

  def addSelfCliquePeer(peerInfo: BrokerInfo): Unit = {
    table += peerInfo.peerId -> PeerStatus.fromInfo(peerInfo)
  }

  def publishNewPeer(peerInfo: BrokerInfo): Unit

  def scan(): Unit = {
    val peerCandidates = table.values.filter(status => status.info.peerId != selfPeerId)
    peerCandidates.foreach(status => ping(status.info))

    val emptySlotNum = neighborMax - getPeersWeight
    val bootstrapNum = if (emptySlotNum > 0) emptySlotNum else 0
    bootstrap
      .filter(address => !peerCandidates.exists(_.info.address == address))
      .take(bootstrapNum)
      .foreach(ping)
  }

  private val fastScanThreshold = TimeStamp.now() + fastScanPeriod
  def shouldScanFast(): Boolean = {
    (TimeStamp.now() < fastScanThreshold) || (!atLeastOnePeerPerGroup())
  }

  def atLeastOnePeerPerGroup(): Boolean = {
    brokerConfig.groupRange.forall { group =>
      table.values.count(
        _.info.contains(GroupIndex.unsafe(group))
      ) >= 2 // peers from self clique is counted
    }
  }

  def fetchNeighbors(info: BrokerInfo): Unit = {
    fetchNeighbors(info.address)
  }

  def fetchNeighbors(remote: InetSocketAddress): Unit = {
    send(remote, FindNode(selfCliqueId))
  }

  def send(remote: InetSocketAddress, payload: Payload): Unit = {
    socketOpt match {
      case Some(socket) =>
        log.debug(s"Send ${payload.getClass.getSimpleName} to $remote")
        val message = DiscoveryMessage.from(selfCliqueId, payload)
        socket ! UdpServer.Send(
          DiscoveryMessage.serialize(message, networkConfig.networkType, selfCliqueInfo.priKey),
          remote
        )
      case None =>
        log.debug(
          s"Udp socket is not available, might be network issues. Ignoring sending $payload to $remote"
        )
    }
  }

  def tryPing(peerInfo: BrokerInfo): Unit = {
    if (isUnknown(peerInfo.peerId) && isPendingAvailable) {
      ping(peerInfo)
    }
  }

  def ping(peerInfo: BrokerInfo): Unit = {
    assume(peerInfo.peerId != selfPeerId)
    val remoteAddress = peerInfo.address
    send(remoteAddress, Ping(selfPeerInfoOpt))
    pendings += (peerInfo.peerId -> AwaitPong(remoteAddress, TimeStamp.now()))
  }

  def ping(remote: InetSocketAddress): Unit = {
    send(remote, Ping(selfPeerInfoOpt))
  }

  def handlePong(peerInfo: BrokerInfo): Unit = {
    val peerId = peerInfo.peerId
    pendings.get(peerId) match {
      case Some(AwaitPong(_, _)) =>
        pendings.remove(peerId)
        if (!isInTable(peerInfo.peerId)) {
          if (getPeersWeight < neighborMax) {
            appendPeer(peerInfo)
          } else {
            tryInsert(peerInfo)
          }
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
