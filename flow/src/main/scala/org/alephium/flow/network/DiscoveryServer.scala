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

import akka.actor.{ActorRef, Cancellable, Props, Stash, Terminated}

import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.network.udp.UdpServer
import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig, NetworkConfig}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage._
import org.alephium.protocol.model.{BrokerGroupInfo, BrokerInfo, CliqueInfo}
import org.alephium.util._

object DiscoveryServer {
  def props(
      bindAddress: InetSocketAddress,
      misbehaviorManager: ActorRefT[MisbehaviorManager.Command],
      bootstrap: ArraySeq[InetSocketAddress]
  )(implicit
      brokerConfig: BrokerConfig,
      discoveryConfig: DiscoveryConfig,
      networkConfig: NetworkConfig
  ): Props =
    Props(new DiscoveryServer(bindAddress, misbehaviorManager, bootstrap))

  def props(
      bindAddress: InetSocketAddress,
      misbehaviorManager: ActorRefT[MisbehaviorManager.Command],
      peers: InetSocketAddress*
  )(implicit
      brokerConfig: BrokerConfig,
      discoveryConfig: DiscoveryConfig,
      networkConfig: NetworkConfig
  ): Props = {
    props(bindAddress, misbehaviorManager, ArraySeq.from(peers))
  }

  final case class PeerStatus(info: BrokerInfo, updateAt: TimeStamp)
  object PeerStatus {
    def fromInfo(info: BrokerInfo): PeerStatus = {
      PeerStatus(info, TimeStamp.now())
    }
  }

  final case class AwaitReply(remote: InetSocketAddress, requestAt: TimeStamp)

  sealed trait Command
  final case class GetNeighborPeers(targetGroupInfoOpt: Option[BrokerGroupInfo]) extends Command
  final case class Remove(peer: InetSocketAddress)                               extends Command
  final case class Unban(peers: AVector[InetAddress])                            extends Command
  case object Scan                                                               extends Command
  final case class SendCliqueInfo(cliqueInfo: CliqueInfo)                        extends Command
  final case class PeerConfirmed(peerInfo: BrokerInfo)                           extends Command
  final case class PeerDenied(peerInfo: BrokerInfo)                              extends Command
  final case class Unreachable(remote: InetSocketAddress)                        extends Command with EventStream.Event
  case object GetUnreachable                                                     extends Command

  sealed trait Event
  final case class NeighborPeers(peers: AVector[BrokerInfo]) extends Event
  final case class NewPeer(info: BrokerInfo)                 extends Event with EventStream.Event
}

/*
 * This variant of Kademlia protocol follows these rules:
 *  -> send ping to another peer to detect the liveness
 *  -> pong back when received valid ping
 *  -> ping back to verify peer address when received ping for the first time from another peer
 *  -> send find_node to discover peers
 *  -> send neighbors back when received find_node
 *  -> ping all the new neighbors received from peers
 *  -> send find_node periodically to peers to update knowledge of neighbors
 *
 *
 *  TODO: each group has several buckets instead of just one bucket
 */
class DiscoveryServer(
    val bindAddress: InetSocketAddress,
    val misbehaviorManager: ActorRefT[MisbehaviorManager.Command],
    val bootstrap: ArraySeq[InetSocketAddress]
)(implicit
    val brokerConfig: BrokerConfig,
    val discoveryConfig: DiscoveryConfig,
    val networkConfig: NetworkConfig
) extends BaseActor
    with Stash
    with DiscoveryServerState
    with EventStream {

  import DiscoveryServer._

  def receive: Receive = awaitCliqueInfo

  var selfCliqueInfo: CliqueInfo = _

  var scanScheduled: Option[Cancellable] = None

  val udpServer: ActorRef = context.actorOf(UdpServer.props())

  def awaitCliqueInfo: Receive = {
    case SendCliqueInfo(cliqueInfo) =>
      selfCliqueInfo = cliqueInfo
      cliqueInfo.interBrokers.foreach { brokers => brokers.foreach(addSelfCliquePeer) }
      unstashAll()
      log.debug(s"bootstrap nodes: ${bootstrap.mkString(";")}")
      startBinding()

    case _ =>
      stash()
  }

  def startBinding(): Unit = {
    udpServer ! UdpServer.Bind(bindAddress)
    context become binding // binding will stash messages
  }

  def binding: Receive = {
    case UdpServer.Bound(address) =>
      log.info(s"UDP server bound to $address")
      setSocket(ActorRefT[UdpServer.Command](sender()))
      context.watch(sender())
      bootstrap.foreach(fetchNeighbors)
      scheduleScan()
      unstashAll()
      context.become(ready)
    case UdpServer.BindFailed =>
      log.error(s"Could not bind the UDP socket, shutdown system")
      terminateSystem()

    case _ =>
      stash()
  }

  def ready: Receive = {
    subscribeEvent(self, classOf[Unreachable])
    subscribeEvent(self, classOf[MisbehaviorManager.PeerBanned])
    handleUdp orElse handleCommand orElse handleBanning
  }

  def handleUdp: Receive = {
    case UdpServer.Received(data, remote) =>
      DiscoveryMessage.deserialize(data) match {
        case Right(message: DiscoveryMessage) =>
          log.debug(s"Received ${message.payload.getClass.getSimpleName} from $remote")
          handlePayload(remote)(message.payload)
        case Left(error) =>
          // TODO: handler error properly
          log.warning(s"Received corrupted UDP data from $remote (${data.size} bytes): $error")
          misbehaviorManager ! MisbehaviorManager.SerdeError(remote)
      }
    case Terminated(_) =>
      log.error(s"Udp listener stopped, there might be network issue")
      unsetSocket()
      cancelScan()
      context.stop(self)
  }

  def handleCommand: Receive = {
    case Scan =>
      cleanup()
      log.debug(s"Scanning peers: $getPeersNum in total")
      scanAndSchedule()
    case GetNeighborPeers(targetGroupInfoOpt) =>
      sender() ! NeighborPeers(getActivePeers(targetGroupInfoOpt))

    case Remove(peer) => remove(peer)
    case PeerDenied(peerInfo) =>
      log.debug(s"peer ${peerInfo.peerId} - ${peerInfo.address} is banned, ignoring it")
      banPeer(peerInfo.peerId)
    case PeerConfirmed(peerInfo) =>
      tryPing(peerInfo)
    case Unreachable(remote) => setUnreachable(remote)
    case GetUnreachable      => sender() ! getUnreachable()
    case Unban(remotes)      => remotes.foreach(unsetUnreachable)
  }

  def handleBanning: Receive = { case MisbehaviorManager.PeerBanned(peer) =>
    banPeerFromAddress(peer)
  }

  def handlePayload(remote: InetSocketAddress)(payload: Payload): Unit =
    payload match {
      case Ping(id, senderInfo) =>
        senderInfo match {
          case None =>
            selfPeerInfoOpt.foreach(info => send(remote, Pong(id, info)))
          case Some(info) =>
            selfPeerInfoOpt.foreach(info => send(remote, Pong(id, info)))
            misbehaviorManager ! MisbehaviorManager.ConfirmPeer(info)
        }
      case Pong(id, peerInfo) =>
        handlePong(id, peerInfo)
      case FindNode(targetId) =>
        val neighbors = getNeighbors(targetId)
        send(remote, Neighbors(neighbors))
      case Neighbors(peers) =>
        peers.foreach { peerInfo =>
          if (isUnknown(peerInfo.peerId)) {
            misbehaviorManager ! MisbehaviorManager.ConfirmPeer(peerInfo)
          }
        }
    }

  override def publishNewPeer(peerInfo: BrokerInfo): Unit = {
    if (mightReachableSlow(peerInfo.address)) {
      publishEvent(NewPeer(peerInfo))
    }
  }

  private def scanAndSchedule(): Unit = {
    scan()
    scheduleScan()
  }

  private def scheduleScan(): Unit = {
    val frequency = if (shouldScanFast()) {
      discoveryConfig.scanFastFrequency
    } else {
      discoveryConfig.scanFrequency
    }

    scanScheduled.foreach(_.cancel())
    scanScheduled = Some(scheduleCancellableOnce(self, Scan, frequency))
  }

  def cancelScan(): Unit = {
    scanScheduled.foreach(_.cancel())
    scanScheduled = None
  }
}
