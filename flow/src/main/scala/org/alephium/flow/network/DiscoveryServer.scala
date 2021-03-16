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

import akka.actor.{Cancellable, Props, Stash, Terminated, Timers}
import akka.io.{IO, Udp}
import akka.io.Udp.SO

import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig, NetworkConfig}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage._
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo, PeerId}
import org.alephium.util._

object DiscoveryServer {
  def props(bindAddress: InetSocketAddress,
            misbehaviorManager: ActorRefT[MisbehaviorManager.Command],
            bootstrap: ArraySeq[InetSocketAddress])(implicit brokerConfig: BrokerConfig,
                                                    discoveryConfig: DiscoveryConfig,
                                                    networkConfig: NetworkConfig): Props =
    Props(new DiscoveryServer(bindAddress, misbehaviorManager, bootstrap))

  def props(bindAddress: InetSocketAddress,
            misbehaviorManager: ActorRefT[MisbehaviorManager.Command],
            peers: InetSocketAddress*)(implicit brokerConfig: BrokerConfig,
                                       discoveryConfig: DiscoveryConfig,
                                       networkConfig: NetworkConfig): Props = {
    props(bindAddress, misbehaviorManager, ArraySeq.from(peers))
  }

  final case class PeerStatus(info: BrokerInfo, updateAt: TimeStamp)
  object PeerStatus {
    def fromInfo(info: BrokerInfo): PeerStatus = {
      PeerStatus(info, TimeStamp.now())
    }
  }

  object Timer

  final case class AwaitPong(remote: InetSocketAddress, pingAt: TimeStamp)

  sealed trait Command
  case object GetNeighborPeers                               extends Command
  final case class Disable(peerId: PeerId)                   extends Command
  final case class Remove(peer: InetSocketAddress)           extends Command
  case object Scan                                           extends Command
  final case class SendCliqueInfo(cliqueInfo: CliqueInfo)    extends Command
  final case class PeerConfirmed(peerInfo: BrokerInfo)       extends Command
  final case class PeerDenied(peerInfo: BrokerInfo)          extends Command
  final case class PeerDisconnected(peer: InetSocketAddress) extends Command
  case object RestartUdp                                     extends Command

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
class DiscoveryServer(val bindAddress: InetSocketAddress,
                      val misbehaviorManager: ActorRefT[MisbehaviorManager.Command],
                      val bootstrap: ArraySeq[InetSocketAddress])(
    implicit val brokerConfig: BrokerConfig,
    val discoveryConfig: DiscoveryConfig,
    val networkConfig: NetworkConfig)
    extends BaseActor
    with Stash
    with Timers
    with DiscoveryServerState
    with EventStream {

  import DiscoveryServer._

  def receive: Receive = awaitCliqueInfo

  var selfCliqueInfo: CliqueInfo = _

  var scanScheduled: Cancellable = _

  def awaitCliqueInfo: Receive = {
    case SendCliqueInfo(cliqueInfo) =>
      selfCliqueInfo = cliqueInfo
      cliqueInfo.interBrokers.foreach { brokers =>
        brokers.foreach(addSelfCliquePeer)
      }
      unstashAll()
      startBinding()

    case _ => stash()
  }

  def startBinding(): Unit = {
    IO(Udp)(context.system) ! Udp.Bind(self, bindAddress, options = Seq(SO.ReuseAddress(true)))
    context become (handleCommand orElse binding) // binding will stash messages
  }

  def binding: Receive = {
    case Udp.Bound(address) =>
      log.debug(s"UDP server bound to $address")
      setSocket(ActorRefT[Udp.Command](sender()))
      context.watch(sender()) // upd listener might crash when there are network issues
      log.debug(s"bootstrap nodes: ${bootstrap.mkString(";")}")
      bootstrap.foreach(ping)
      scheduleScan()
      unstashAll()
      context.become(ready)
    case Udp.CommandFailed(_) =>
      log.warning(s"Could not bind the UDP socket. Restarting it in 1 second")
      scheduleOnce(self, RestartUdp, Duration.ofSecondsUnsafe(1))
    case RestartUdp => startBinding()

    case _ => stash()
  }

  def ready: Receive = handleUdp orElse handleCommand orElse handleBanning

  def handleUdp: Receive = {
    case Udp.Received(data, remote) =>
      DiscoveryMessage.deserialize(selfCliqueId, data, networkConfig.networkType) match {
        case Right(message: DiscoveryMessage) =>
          log.debug(s"Received ${message.payload} from $remote")
          handlePayload(remote)(message.payload)
        case Left(error) =>
          // TODO: handler error properly
          log.debug(s"Received corrupted UDP data from $remote (${data.size} bytes): $error")
      }
    case Terminated(_) =>
      log.warning(s"Udp listener stopped, there might be network issue. Restarting it in 1 second")
      unsetSocket()
      scanScheduled.cancel()
      scheduleOnce(self, RestartUdp, Duration.ofSecondsUnsafe(1))
    case RestartUdp => startBinding()
  }

  def handleCommand: Receive = {
    case Scan =>
      cleanup()
      log.debug(s"Scanning peers: $getPeersNum in total")
      scanAndSchedule()
      ()
    case GetNeighborPeers =>
      sender() ! NeighborPeers(getActivePeers)
    case Disable(peerId) =>
      table -= peerId
      ()
    case Remove(peer) =>
      remove(peer)
    case PeerDenied(peerInfo) =>
      log.debug(s"peer ${peerInfo.peerId} - ${peerInfo.address} is banned, ignoring it")
      banPeer(peerInfo.peerId)
    case PeerConfirmed(peerInfo) =>
      tryPing(peerInfo)
    case PeerDisconnected(peer) =>
      scanScheduled.cancel()
      remove(peer)
      scanAndSchedule()
  }

  def handleBanning: Receive = {
    case MisbehaviorManager.PeerBanned(peer) =>
      if (banPeerFromAddress(peer)) {
        scanScheduled.cancel()
        scanAndSchedule()
      }
  }

  def handlePayload(remote: InetSocketAddress)(payload: Payload): Unit =
    payload match {
      case Ping(peerInfoOpt) =>
        peerInfoOpt match {
          case None =>
            selfPeerInfoOpt.foreach(info => send(remote, Pong(info)))
          case Some(peerInfo) =>
            validatePeerInfo(remote, peerInfo) { validPeerInfo =>
              selfPeerInfoOpt.foreach(info => send(remote, Pong(info)))
              misbehaviorManager ! MisbehaviorManager.ConfirmPeer(validPeerInfo)
            }
        }
      case Pong(peerInfo) =>
        validatePeerInfo(remote, peerInfo) { validPeerInfo =>
          handlePong(validPeerInfo)
        }
      case FindNode(targetId) =>
        val neighbors = getNeighbors(targetId)
        send(remote, Neighbors(neighbors))
      case Neighbors(peers) =>
        peers.foreach { peerInfo =>
          if (!table.contains(peerInfo.peerId)) {
            misbehaviorManager ! MisbehaviorManager.ConfirmPeer(peerInfo)
          }
        }
    }

  override def publishNewPeer(peerInfo: BrokerInfo): Unit = {
    publishEvent(NewPeer(peerInfo))
  }

  private def scanAndSchedule() = {
    scan()
    scheduleScan()
  }

  private def scheduleScan() = {
    val frequnecy = if (shouldScanFast()) {
      discoveryConfig.scanFastFrequency
    } else {
      discoveryConfig.scanFrequency
    }

    scanScheduled = scheduleCancellableOnce(self, Scan, frequnecy)
  }

  private def validatePeerInfo(remote: InetSocketAddress, peerInfo: BrokerInfo)(
      f: BrokerInfo => Unit): Unit = {
    if (remote == peerInfo.address) {
      f(peerInfo)
    } else {
      log.warning(s"Peer info mismatch with remote address: ${peerInfo.address} <> ${remote}")
      misbehaviorManager ! MisbehaviorManager.InvalidMessage(remote)
    }
  }
}
