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

  lazy val maxSentPeers                        = 20
  lazy val selfPeerId: PeerId                  = selfCliqueInfo.selfInterBrokerInfo.peerId
  lazy val selfPeerInfoOpt: Option[BrokerInfo] = selfCliqueInfo.selfBrokerInfo
  lazy val selfCliqueBrokerInfosOpt: Option[AVector[BrokerInfo]] = selfCliqueInfo.interBrokers
  lazy val tableInitialSize = selfCliqueBrokerInfosOpt.map(_.length).getOrElse(0)

  import DiscoveryServer._

  private var socketOpt: Option[ActorRefT[UdpServer.Command]] = None

  private val neighborMax = discoveryConfig.neighborsPerGroup * brokerConfig.groups

  protected val table               = mutable.HashMap.empty[PeerId, PeerStatus]
  private[network] val unreachables = Cache.fifo[InetAddress, TimeStamp](neighborMax)

  def setSocket(s: ActorRefT[UdpServer.Command]): Unit = {
    socketOpt = Some(s)
  }

  def unsetSocket(): Unit = {
    socketOpt = None
  }

  def getActivePeers(): AVector[BrokerInfo] = {
    AVector
      .from(table.values.map(_.info))
      .filter(info => mightReachable(info.address))
      .sortBy(broker => selfCliqueId.hammingDist(broker.cliqueId))
  }

  def getMorePeers(targetGroupInfo: BrokerGroupInfo): AVector[BrokerInfo] = {
    val candidates = table.values
      .map(_.info)
      .filter { info =>
        targetGroupInfo.intersect(info) &&
        mightReachable(info.address) &&
        info.cliqueId != selfCliqueId
      }
    val randomCliqueId = CliqueId.generate
    AVector
      .from(candidates)
      .sortBy(info => randomCliqueId.hammingDist(info.cliqueId))
      .takeUpto(maxSentPeers)
  }

  def getPeersNum: Int = table.size

  def getPeersWeight: Int = {
    table.values.foldLeft(0) { case (weight, peerStatus) =>
      weight + brokerConfig.remoteGroupNum(peerStatus.info)
    }
  }

  // select a number of random peers based on a random clique id
  def getBootstrapNeighbors(): AVector[BrokerInfo] = {
    getNeighbors(selfCliqueId, CliqueId.generate)
  }

  def getNeighbors(target: CliqueId): AVector[BrokerInfo] = {
    getNeighbors(target, target)
  }

  def getNeighbors(filterId: CliqueId, target: CliqueId): AVector[BrokerInfo] = {
    val candidates = AVector.from(table.values.map(_.info).filter(_.cliqueId != filterId))
    candidates
      .sortBy(info => target.hammingDist(info.cliqueId))
      .takeUpto(maxSentPeers)
  }

  def isInTable(peerId: PeerId): Boolean = {
    table.contains(peerId)
  }

  def isUnknown(peerId: PeerId): Boolean = !isInTable(peerId) && !isPending(peerId)

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
    setUnreachableForBan(address)
  }

  def banPeer(peerId: PeerId): Unit = {
    table.get(peerId).foreach { status =>
      removeBroker(peerId)
      setUnreachable(status.info.address)
    }
  }

  def cleanup(): Unit = {
    val now = TimeStamp.now()
    cleanTable(now)
    cleanSessions(now)
    cleanUnreachables(now)
  }

  def cleanTable(now: TimeStamp): Unit = {
    table.filterInPlace { case (peerId, status) =>
      val keep = now.deltaUnsafe(status.updateAt) < discoveryConfig.expireDuration ||
        peerId.cliqueId == selfCliqueId
      if (!keep) removeBrokerFromStorage(peerId)
      keep
    }
    DiscoveryServer.discoveredBrokerSize.set(table.size.toDouble)
  }

  def getUnreachable(): AVector[InetAddress] = {
    AVector.from(unreachables.keys())
  }

  def updateUnreachable(remote: InetAddress, duration: Duration): Unit = {
    unreachables.get(remote) match {
      case Some(until) =>
        unreachables.put(remote, until + duration)
      case None =>
        unreachables.put(remote, TimeStamp.now() + duration)
    }
  }

  def updateUnreachable(remote: InetAddress): Unit = {
    updateUnreachable(remote, discoveryConfig.unreachableDuration)
  }

  def setUnreachable(remote: InetSocketAddress): Unit = {
    setUnreachable(remote.getAddress)
  }

  def setUnreachable(remote: InetAddress): Unit = {
    updateUnreachable(remote)
    remove(remote)
  }

  def setUnreachableForBan(remote: InetAddress): Unit = {
    updateUnreachable(remote, Duration.ofDaysUnsafe(1))
  }

  def unsetUnreachable(remote: InetAddress): Unit = {
    unreachables.remove(remote)
    ()
  }

  def mightReachableSlow(remote: InetSocketAddress): Boolean = {
    val remoteInet = remote.getAddress
    unreachables.get(remoteInet) match {
      case None => true
      case Some(until) =>
        if (until <= TimeStamp.now()) {
          unreachables.remove(remoteInet)
          true
        } else {
          false
        }
    }
  }

  def mightReachable(remote: InetSocketAddress): Boolean = {
    !unreachables.contains(remote.getAddress)
  }

  def cleanUnreachables(now: TimeStamp): Unit = {
    val toRemove = AVector.from(unreachables.entries().filter(_.getValue <= now).map(_.getKey))
    toRemove.foreach(unreachables.remove)
  }

  def getCliqueNumPerIp(target: BrokerInfo): Int = {
    table.values.view
      .filter(_.info.isFromSameIp(target))
      .map(_.info.cliqueId)
      .toSet
      .size
  }

  def appendPeer(peerInfo: BrokerInfo): Unit = {
    if (getCliqueNumPerIp(peerInfo) < discoveryConfig.maxCliqueFromSameIp) {
      log.info(s"Adding a new peer: $peerInfo")
      addBroker(peerInfo)
      publishNewPeer(peerInfo)
    } else {
      log.debug(s"Too many cliques from a same IP: $peerInfo")
    }
  }

  @inline final def addBroker(peerInfo: BrokerInfo): Unit = {
    addBrokerToStorage(peerInfo)
    table += peerInfo.peerId -> PeerStatus.fromInfo(peerInfo)
    DiscoveryServer.discoveredBrokerSize.set(table.size.toDouble)
  }

  @inline final def cacheBrokers(peers: AVector[BrokerInfo]): Unit = {
    peers.foreach(peer => table += peer.peerId -> PeerStatus.fromInfo(peer))
    DiscoveryServer.discoveredBrokerSize.set(table.size.toDouble)
  }

  @inline private def removeBroker(peerId: PeerId): Unit = {
    table -= peerId
    removeBrokerFromStorage(peerId)
    DiscoveryServer.discoveredBrokerSize.set(table.size.toDouble)
  }

  @inline private def removeBrokers(peerIds: Iterable[PeerId]): Unit = {
    table --= peerIds
    peerIds.foreach(removeBrokerFromStorage)
    DiscoveryServer.discoveredBrokerSize.set(table.size.toDouble)
  }

  def publishNewPeer(peerInfo: BrokerInfo): Unit
  def addBrokerToStorage(peerInfo: BrokerInfo): Unit
  def removeBrokerFromStorage(peerId: PeerId): Unit

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
          DiscoveryMessage.serialize(message, selfCliqueInfo.priKey),
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
    removeFromTable(_.address == peer)
    removeFromSession(_.remote == peer)
  }

  def remove(peer: InetAddress): Unit = {
    removeFromTable(_.address.getAddress == peer)
    removeFromSession(_.remote.getAddress == peer)
  }

  def removeFromTable(func: BrokerInfo => Boolean): Unit = {
    val peersToRemove = table.values.filter(peer => func(peer.info)).map(_.info.peerId)
    removeBrokers(peersToRemove)
  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  private def tryInsert(peerInfo: BrokerInfo): Unit = {
    val myself   = selfCliqueId
    val furthest = table.keys.maxBy(peerId => myself.hammingDist(peerId.cliqueId))
    if (myself.hammingDist(peerInfo.cliqueId) < myself.hammingDist(furthest.cliqueId)) {
      removeBroker(furthest)
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
    sessions.remove(id).exists { session =>
      pendings.remove(brokerInfo.peerId)
      session.remote == brokerInfo.address
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

  def removeFromSession(func: AwaitReply => Boolean): Unit = {
    sessions.removeIf { case (_, awaitReply: AwaitReply) =>
      func(awaitReply)
    }
  }
}
