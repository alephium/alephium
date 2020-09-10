package org.alephium.flow.network

import java.net.InetSocketAddress

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

import akka.event.LoggingAdapter
import akka.io.Udp

import org.alephium.protocol.config.{DiscoveryConfig, GroupConfig}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage._
import org.alephium.protocol.model.{CliqueId, CliqueInfo, InterCliqueInfo}
import org.alephium.util.{ActorRefT, AVector, TimeStamp}

trait DiscoveryServerState {
  implicit def groupConfig: GroupConfig
  implicit def discoveryConfig: DiscoveryConfig
  def log: LoggingAdapter

  def bootstrap: ArraySeq[InetSocketAddress]
  def selfCliqueInfo: CliqueInfo
  def selfCliqueId: CliqueId = selfCliqueInfo.id

  lazy val selfInterCliqueInfoOpt: Option[InterCliqueInfo] = selfCliqueInfo.interCliqueInfo

  import DiscoveryServer._

  private var socket: ActorRefT[Udp.Command] = _
  protected val table                        = mutable.HashMap.empty[CliqueId, PeerStatus]
  private val pendings                       = mutable.HashMap.empty[CliqueId, AwaitPong]
  private val pendingMax                     = 2 * groupConfig.groups * discoveryConfig.neighborsPerGroup

  def setSocket(s: ActorRefT[Udp.Command]): Unit = {
    socket = s
  }

  def getActivePeers: AVector[InterCliqueInfo] = {
    AVector.from(table.values.map(_.info))
  }

  def getPeersNum: Int = table.size

  def getNeighbors(target: CliqueId): AVector[InterCliqueInfo] = {
    val candidates = if (target == selfCliqueId) {
      AVector.from(table.values.map(_.info))
    } else {
      val neighbors = AVector.from(table.values.map(_.info).filter(_.id != target))
      selfInterCliqueInfoOpt.fold(neighbors)(neighbors :+ _)
    }
    candidates
      .sortBy(info => target.hammingDist(info.id))
      .takeUpto(discoveryConfig.neighborsPerGroup)
  }

  def isInTable(cliqueId: CliqueId): Boolean = {
    table.contains(cliqueId)
  }

  def isPending(cliqueId: CliqueId): Boolean = {
    pendings.contains(cliqueId)
  }

  def isUnknown(cliqueId: CliqueId): Boolean = !isInTable(cliqueId) && !isPending(cliqueId)

  def isPendingAvailable: Boolean = pendings.size < pendingMax

  def getPeer(cliqueId: CliqueId): Option[InterCliqueInfo] = {
    table.get(cliqueId).map(_.info)
  }

  def getPending(cliqueId: CliqueId): Option[AwaitPong] = {
    pendings.get(cliqueId)
  }

  def updateStatus(cliqueId: CliqueId): Unit = {
    table.get(cliqueId) match {
      case Some(status) =>
        table(cliqueId) = status.copy(updateAt = TimeStamp.now())
      case None => ()
    }
  }

  def getPendingStatus(cliqueId: CliqueId): Option[AwaitPong] = {
    pendings.get(cliqueId)
  }

  def cleanup(): Unit = {
    val now = TimeStamp.now()
    val expired = table.values
      .filter(status => (now -- status.updateAt).exists(_ >= discoveryConfig.expireDuration))
      .map(_.info.id)
      .toSet
    table --= expired

    val deadPendings = pendings.collect {
      case (cliqueId, status) if (now -- status.pingAt).exists(_ >= discoveryConfig.peersTimeout) =>
        cliqueId
    }
    pendings --= deadPendings
  }

  private def appendPeer(cliqueInfo: InterCliqueInfo): Unit = {
    log.debug(s"Adding a new clique: $cliqueInfo")
    table += cliqueInfo.id -> PeerStatus.fromInfo(cliqueInfo)
    fetchNeighbors(cliqueInfo)
    publishNewClique(cliqueInfo)
  }

  def publishNewClique(cliqueInfo: InterCliqueInfo): Unit

  // TODO: improve scan algorithm
  def scan(): Unit = {
    val sortedNeighbors =
      AVector.from(table.values).sortBy(status => selfCliqueId.hammingDist(status.info.id))
    sortedNeighbors
      .takeUpto(discoveryConfig.scanMaxPerGroup)
      .foreach(status => fetchNeighbors(status.info))
    val emptySlotNum = discoveryConfig.scanMaxPerGroup - sortedNeighbors.length
    val bootstrapNum = if (emptySlotNum > 0) emptySlotNum else 0
    bootstrap.take(bootstrapNum).foreach(tryPing)
  }

  def shouldScanFast(): Boolean = {
    table.isEmpty
  }

  def fetchNeighbors(info: InterCliqueInfo): Unit = {
    fetchNeighbors(info.externalAddresses.sample())
  }

  def fetchNeighbors(remote: InetSocketAddress): Unit = {
    send(remote, FindNode(selfCliqueId))
  }

  def send(remote: InetSocketAddress, payload: Payload): Unit = {
    val message = DiscoveryMessage.from(selfCliqueId, payload)
    socket ! Udp.Send(DiscoveryMessage.serialize(message), remote)
  }

  def tryPing(cliqueInfo: InterCliqueInfo): Unit = {
    if (isUnknown(cliqueInfo.id) && isPendingAvailable) {
      log.info(s"Sending Ping to $cliqueInfo")
      val remoteAddress = cliqueInfo.externalAddresses.sample()
      send(remoteAddress, Ping(selfInterCliqueInfoOpt))
      pendings += (cliqueInfo.id -> AwaitPong(remoteAddress, TimeStamp.now()))
    }
  }

  def tryPing(remote: InetSocketAddress): Unit = {
    log.debug(s"Sending Ping to $remote")
    send(remote, Ping(selfInterCliqueInfoOpt))
  }

  def handlePong(cliqueInfo: InterCliqueInfo): Unit = {
    val cliqueId = cliqueInfo.id
    pendings.get(cliqueId) match {
      case Some(AwaitPong(_, _)) =>
        pendings.remove(cliqueId)
        if (table.size < discoveryConfig.neighborsPerGroup) {
          appendPeer(cliqueInfo)
        } else {
          tryInsert(cliqueInfo)
        }
      case None =>
        tryPing(cliqueInfo)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  private def tryInsert(cliqueInfo: InterCliqueInfo): Unit = {
    val myself   = selfCliqueId
    val furthest = table.keys.maxBy(myself.hammingDist)
    if (myself.hammingDist(cliqueInfo.id) < myself.hammingDist(furthest)) {
      table -= furthest
      appendPeer(cliqueInfo)
    }
  }
}
