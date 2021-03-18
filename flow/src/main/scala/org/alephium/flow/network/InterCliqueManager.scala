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

import akka.actor.Props
import akka.event.LoggingAdapter
import akka.io.Tcp

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.broker._
import org.alephium.flow.network.interclique.{InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, BaseActor, EventStream}

object InterCliqueManager {
  // scalastyle:off parameter.number
  def props(selfCliqueInfo: CliqueInfo,
            blockflow: BlockFlow,
            allHandlers: AllHandlers,
            discoveryServer: ActorRefT[DiscoveryServer.Command],
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting): Props =
    Props(
      new InterCliqueManager(selfCliqueInfo,
                             blockflow,
                             allHandlers,
                             discoveryServer,
                             blockFlowSynchronizer))
  //scalastyle:on

  sealed trait Command              extends CliqueManager.Command
  final case object GetSyncStatuses extends Command

  final case class SyncStatus(peerId: PeerId, address: InetSocketAddress, isSynced: Boolean)

  final case class BrokerState(info: BrokerInfo,
                               connectionType: ConnectionType,
                               actor: ActorRefT[BrokerHandler.Command],
                               isSynced: Boolean) {
    def setSynced(): BrokerState = this.copy(isSynced = true)

    def readyFor(chainIndex: ChainIndex): Boolean = {
      isSynced && info.contains(chainIndex.from)
    }
  }

  final case class PeerDisconnected(peer: InetSocketAddress) extends EventStream.Event
}

class InterCliqueManager(selfCliqueInfo: CliqueInfo,
                         blockflow: BlockFlow,
                         allHandlers: AllHandlers,
                         discoveryServer: ActorRefT[DiscoveryServer.Command],
                         blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
    implicit val brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting)
    extends BaseActor
    with EventStream.Subscriber
    with InterCliqueManagerState {
  import InterCliqueManager._

  override def preStart(): Unit = {
    super.preStart()
    discoveryServer ! DiscoveryServer.SendCliqueInfo(selfCliqueInfo)
    subscribeEvent(self, classOf[DiscoveryServer.NewPeer])
  }

  override def receive: Receive = handleMessage orElse handleConnection orElse handleNewClique

  def handleNewClique: Receive = {
    case DiscoveryServer.NewPeer(peerInfo) => connect(peerInfo)
  }

  def handleConnection: Receive = {
    case Tcp.Connected(remoteAddress, _) =>
      if (checkForInConnection(maxInboundConnectionsPerGroup)) {
        log.info(s"Connected to $remoteAddress")
        val name = BaseActor.envalidActorName(s"InboundBrokerHandler-${remoteAddress}")
        val props =
          InboundBrokerHandler.props(
            selfCliqueInfo,
            remoteAddress,
            networkSetting.connectionBuild(sender()),
            blockflow,
            allHandlers,
            ActorRefT[CliqueManager.Command](self),
            blockFlowSynchronizer
          )
        val in = context.actorOf(props, name)
        context.watchWith(in, PeerDisconnected(remoteAddress))
        ()
      } else {
        sender() ! Tcp.Close
      }
    case CliqueManager.HandShaked(brokerInfo, connectionType) =>
      if (checkForConnection(brokerInfo, connectionType)) {
        log.debug(s"Start syncing with inter-clique node: $brokerInfo")
        addBroker(brokerInfo, connectionType, ActorRefT(sender()))
      } else {
        context stop sender()
      }
    case CliqueManager.Synced(brokerInfo) =>
      log.debug(s"No new blocks from $brokerInfo")
      setSynced(brokerInfo)
    case _: Tcp.ConnectionClosed => ()
  }

  def handleMessage: Receive = {
    case message: CliqueManager.BroadCastBlock =>
      val block = message.block
      log.debug(s"Broadcasting block ${block.shortHex} for ${block.chainIndex}")
      iterBrokers { (peerId, brokerState) =>
        if (!message.origin.isFrom(peerId.cliqueId) && brokerState.readyFor(block.chainIndex)) {
          log.debug(s"Send block to broker $peerId")
          brokerState.actor ! BrokerHandler.Send(message.blockMsg)
        }
      }

    case message: CliqueManager.BroadCastTx =>
      log.debug(s"Broadcasting tx ${message.tx.id.shortHex} for ${message.chainIndex}")
      iterBrokers { (peerId, brokerState) =>
        if (!message.origin.isFrom(peerId.cliqueId) && brokerState.readyFor(message.chainIndex)) {
          log.debug(s"Send tx to broker $peerId")
          brokerState.actor ! BrokerHandler.Send(message.txMsg)
        }
      }

    case GetSyncStatuses =>
      val syncStatuses: Seq[SyncStatus] = mapBrokers { (peerId, brokerState) =>
        SyncStatus(peerId, brokerState.info.address, brokerState.isSynced)
      }
      sender() ! syncStatuses

    case PeerDisconnected(peer) =>
      log.info(s"Peer disconnected: $peer")
      removeBroker(peer)
      discoveryServer ! DiscoveryServer.PeerDisconnected(peer)
  }

  def connect(broker: BrokerInfo): Unit = {
    if (checkForOutConnection(broker, maxOutboundConnectionsPerGroup)) connectUnsafe(broker)
  }

  private def connectUnsafe(brokerInfo: BrokerInfo): Unit = {
    log.debug(s"Try to connect to $brokerInfo")
    val name = BaseActor.envalidActorName(s"OutboundBrokerHandler-$brokerInfo")
    val props =
      OutboundBrokerHandler.props(selfCliqueInfo,
                                  brokerInfo,
                                  blockflow,
                                  allHandlers,
                                  ActorRefT(self),
                                  blockFlowSynchronizer)
    val out = context.actorOf(props, name)
    context.watchWith(out, PeerDisconnected(brokerInfo.address))
    ()
  }

  def checkForConnection(brokerInfo: BrokerInfo, connectionType: ConnectionType): Boolean =
    connectionType match {
      case InboundConnection  => checkForInConnection(brokerInfo, maxInboundConnectionsPerGroup)
      case OutboundConnection => checkForOutConnection(brokerInfo, maxOutboundConnectionsPerGroup)
    }
}

trait InterCliqueManagerState {
  import InterCliqueManager._

  def log: LoggingAdapter
  implicit def brokerConfig: BrokerConfig

  // The key is (CliqueId, BrokerId)
  private val brokers = collection.mutable.HashMap.empty[PeerId, BrokerState]

  def addBroker(brokerInfo: BrokerInfo,
                connectionType: ConnectionType,
                broker: ActorRefT[BrokerHandler.Command]): Unit = {
    val peerId = brokerInfo.peerId
    if (!brokers.contains(peerId)) {
      brokers += peerId -> BrokerState(brokerInfo, connectionType, broker, isSynced = false)
    } else {
      log.debug(s"Ignore another connection from $peerId")
    }
  }

  def containsBroker(brokerInfo: BrokerInfo): Boolean = {
    brokers.contains(brokerInfo.peerId)
  }

  def iterBrokers(f: (PeerId, BrokerState) => Unit): Unit = {
    brokers.foreach {
      case (peerId, state) => f(peerId, state)
    }
  }

  def mapBrokers[A](f: (PeerId, BrokerState) => A): Seq[A] = {
    brokers.collect {
      case (peerId, state) => f(peerId, state)
    }.toSeq
  }

  def setSynced(brokerInfo: BrokerInfo): Unit = {
    val peerId = brokerInfo.peerId
    brokers.get(peerId) match {
      case Some(state) => brokers(peerId) = state.setSynced()
      case None        => log.warning(s"Unexpected message Synced from $brokerInfo")
    }
  }

  def getOutConnectionPerGroup(groupIndex: GroupIndex): Int = {
    brokers.foldLeft(0) {
      case (count, (_, brokerState)) =>
        if (brokerState.connectionType == OutboundConnection &&
            brokerState.info.contains(groupIndex)) {
          count + 1
        } else {
          count
        }
    }
  }

  def getInConnectionPerGroup(groupIndex: GroupIndex): Int = {
    brokers.foldLeft(0) {
      case (count, (_, brokerState)) =>
        if (brokerState.connectionType == InboundConnection &&
            brokerState.info.contains(groupIndex)) {
          count + 1
        } else {
          count
        }
    }
  }

  def removeBroker(peer: InetSocketAddress): Unit = {
    brokers.filterInPlace { case (_, state) => state.info.address != peer }
  }

  def checkForOutConnection(brokerInfo: BrokerInfo,
                            maxOutboundConnectionsPerGroup: Int): Boolean = {
    brokerConfig.intersect(brokerInfo) && !containsBroker(brokerInfo) && {
      val (groupFrom, groupUntil) = brokerConfig.calIntersection(brokerInfo)
      (groupFrom until groupUntil).exists { group =>
        getOutConnectionPerGroup(GroupIndex.unsafe(group)) < maxOutboundConnectionsPerGroup
      }
    }
  }

  def checkForInConnection(maxInboundConnectionsPerGroup: Int): Boolean = {
    (brokerConfig.groupFrom until brokerConfig.groupUntil).exists { group =>
      getInConnectionPerGroup(GroupIndex.unsafe(group)) < maxInboundConnectionsPerGroup
    }
  }

  def checkForInConnection(brokerInfo: BrokerInfo, maxInboundConnectionsPerGroup: Int): Boolean = {
    brokerConfig.intersect(brokerInfo) && !containsBroker(brokerInfo) && {
      val (groupFrom, groupUntil) = brokerConfig.calIntersection(brokerInfo)
      (groupFrom until groupUntil).exists { group =>
        getInConnectionPerGroup(GroupIndex.unsafe(group)) < maxInboundConnectionsPerGroup
      }
    }
  }
}
