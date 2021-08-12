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

import org.alephium.flow.network.DiscoveryServer.AwaitReply
import org.alephium.flow.network.udp.UdpServer
import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig, NetworkConfig}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage._
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector, Cache, Duration, TimeStamp}

// scalastyle:off number.of.methods
trait DiscoveryServerState extends SessionManager {
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

  private val neighborMax = discoveryConfig.neighborsPerGroup * brokerConfig.groups

  protected val table      = mutable.HashMap.empty[PeerId, PeerStatus]
  private val unreachables = Cache.fifo[InetSocketAddress, TimeStamp](neighborMax)

  def setSocket(s: ActorRefT[UdpServer.Command]): Unit = {
    socketOpt = Some(s)
  }

  def unsetSocket(): Unit = {
    socketOpt = None
  }

  def getActivePeers(targetGroupInfoOpt: Option[BrokerGroupInfo]): AVector[BrokerInfo] = {
    val candidates = AVector
      .from(table.values.map(_.info))
      .filter(info => mightReachable(info.address))
      .sortBy(broker => selfCliqueId.hammingDist(broker.cliqueId))
    targetGroupInfoOpt match {
      case Some(groupInfo) => candidates.filter(_.interBrokerInfo.intersect(groupInfo))
      case None            => candidates
    }
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

  def isUnknown(peerId: PeerId): Boolean = !isInTable(peerId) && !isPending(peerId)

  def getPeer(peerId: PeerId): Option[BrokerInfo] = {
    table.get(peerId).map(_.info)
  }

  def updateStatus(peerId: PeerId): Unit = {
    table.get(peerId) match {
      case Some(status) =>
        table(peerId) = status.copy(updateAt = TimeStamp.now())
      case None => ()
    }
  }

  def banPeerFromAddress(address: InetAddress): Unit = {
    table.values.foreach { status =>
      if (status.info.address.getAddress == address) {
        banPeer(status.info.peerId)
      }
    }
  }

  def banPeer(peerId: PeerId): Unit = {
    table.get(peerId).foreach { status =>
      table.remove(peerId)
      setUnreachable(status.info.address)
    }
    table -= peerId
  }

  def cleanup(): Unit = {
    val now = TimeStamp.now()
    cleanTable(now)
    cleanSessions(now)
    cleanUnreachables(now)
  }

  def cleanTable(now: TimeStamp): Unit = {
    table.filterInPlace { case (peerId, status) =>
      now.deltaUnsafe(status.updateAt) < discoveryConfig.expireDuration ||
        peerId.cliqueId == selfCliqueId
    }
    ()
  }

  def setUnreachable(remote: InetSocketAddress): Unit = {
    unreachables.get(remote) match {
      case Some(until) => unreachables.put(remote, until + Duration.ofMinutesUnsafe(1))
      case None        => unreachables.put(remote, TimeStamp.now().plusMinutesUnsafe(1))
    }
    remove(remote)
  }

  def unsetUnreachable(remote: InetAddress): Unit = {
    val toRemove = AVector.from(unreachables.keys().filter(_.getAddress == remote))
    toRemove.foreach(unreachables.remove)
  }

  def mightReachable(remote: InetSocketAddress): Boolean = {
    !unreachables.contains(remote)
  }

  def cleanUnreachables(now: TimeStamp): Unit = {
    val toRemove = AVector.from(unreachables.entries().filter(_.getValue <= now).map(_.getKey))
    toRemove.foreach(unreachables.remove)
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
      .foreach(ping(_, None))
  }

  private val fastScanThreshold = TimeStamp.now() + discoveryConfig.fastScanPeriod
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
        val message = DiscoveryMessage.from(payload)
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
    if (isUnknown(peerInfo.peerId)) {
      ping(peerInfo)
    }
  }

  def ping(peerInfo: BrokerInfo): Unit = {
    if (peerInfo.peerId != selfPeerId) {
      val remoteAddress = peerInfo.address
      ping(remoteAddress, Some(peerInfo))
    }
  }

  def ping(remote: InetSocketAddress, peerInfoOpt: Option[BrokerInfo]): Unit = {
    if (mightReachable(remote)) {
      withNewSession(remote, peerInfoOpt)(sessionId =>
        send(remote, Ping(sessionId, selfPeerInfoOpt))
      )
    }
  }

  def handlePong(id: Id, peerInfo: BrokerInfo): Unit = {
    if (validateSessionId(id, peerInfo)) {
      val peerId = peerInfo.peerId
      if (isInTable(peerId)) {
        updateStatus(peerId)
      } else {
        if (getPeersWeight < neighborMax) {
          appendPeer(peerInfo)
        } else {
          tryInsert(peerInfo)
        }
      }
      fetchNeighbors(peerInfo)
    }
  }

  def remove(peer: InetSocketAddress): Unit = {
    removeFromTable(peer)
    removeFromSession(peer)
  }

  def removeFromTable(peer: InetSocketAddress): Unit = {
    val peersToRemove = table.values.filter(_.info.address == peer).map(_.info.peerId)
    table --= peersToRemove
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

trait SessionManager {
  import DiscoveryMessage.Id

  def brokerConfig: BrokerConfig
  def discoveryConfig: DiscoveryConfig

  private val pendingMax = 20 * brokerConfig.groups * discoveryConfig.neighborsPerGroup

  val sessions = Cache.fifo[Id, AwaitReply](pendingMax)
  val pendings = Cache.fifo[PeerId, TimeStamp](pendingMax)

  def isPending(peerId: PeerId): Boolean = pendings.contains(peerId)

  def withNewSession[T](remote: InetSocketAddress, peerInfoOpt: Option[BrokerInfo])(
      f: Id => T
  ): T = {
    val id  = Id.random()
    val now = TimeStamp.now()
    sessions.put(id, AwaitReply(remote, now))
    peerInfoOpt.foreach(peer => pendings.put(peer.peerId, now))
    f(id)
  }

  def validateSessionId(id: Id, brokerInfo: BrokerInfo): Boolean = {
    sessions.get(id).exists { session =>
      sessions.remove(id)
      pendings.remove(brokerInfo.peerId)
      if (session.remote == brokerInfo.address) {
        true
      } else {
        false
      }
    }
  }

  def cleanSessions(now: TimeStamp): Unit = {
    sessions.removeIf { case (_, status) =>
      now.deltaUnsafe(status.requestAt) >= discoveryConfig.peersTimeout
    }
    pendings.removeIf { case (_, timestamp) =>
      now.deltaUnsafe(timestamp) >= discoveryConfig.peersTimeout
    }
  }

  def removeFromSession(peer: InetSocketAddress): Unit = {
    sessions.removeIf { case (_, awaitReply: AwaitReply) =>
      awaitReply.remote == peer
    }
  }
}
