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

import akka.actor.{Props, Timers}
import akka.io.{IO, Udp}

import org.alephium.flow.FlowMonitor
import org.alephium.protocol.config.{DiscoveryConfig, GroupConfig, NetworkConfig}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.message.DiscoveryMessage._
import org.alephium.protocol.model.{CliqueId, CliqueInfo, InterCliqueInfo}
import org.alephium.util.{ActorRefT, AVector, BaseActor, TimeStamp}

object DiscoveryServer {
  def props(bindAddress: InetSocketAddress, bootstrap: ArraySeq[InetSocketAddress])(
      implicit groupConfig: GroupConfig,
      discoveryConfig: DiscoveryConfig,
      networkConfig: NetworkConfig): Props =
    Props(new DiscoveryServer(bindAddress, bootstrap))

  def props(bindAddress: InetSocketAddress, peers: InetSocketAddress*)(
      implicit groupConfig: GroupConfig,
      discoveryConfig: DiscoveryConfig,
      networkConfig: NetworkConfig): Props = {
    props(bindAddress, ArraySeq.from(peers))
  }

  final case class PeerStatus(info: InterCliqueInfo, updateAt: TimeStamp)
  object PeerStatus {
    def fromInfo(info: InterCliqueInfo): PeerStatus = {
      PeerStatus(info, TimeStamp.now())
    }
  }

  object Timer

  final case class AwaitPong(remote: InetSocketAddress, pingAt: TimeStamp)

  sealed trait Command
  case object GetNeighborCliques                          extends Command
  final case class Disable(cliqueId: CliqueId)            extends Command
  case object Scan                                        extends Command
  final case class SendCliqueInfo(cliqueInfo: CliqueInfo) extends Command

  sealed trait Event
  final case class NeighborCliques(peers: AVector[InterCliqueInfo]) extends Event
  final case class NewClique(info: InterCliqueInfo)                 extends Event
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
                      val bootstrap: ArraySeq[InetSocketAddress])(
    implicit val groupConfig: GroupConfig,
    val discoveryConfig: DiscoveryConfig,
    val networkConfig: NetworkConfig)
    extends BaseActor
    with Timers
    with DiscoveryServerState {
  import context.system

  import DiscoveryServer._

  def receive: Receive = awaitCliqueInfo

  var selfCliqueInfo: CliqueInfo = _

  def awaitCliqueInfo: Receive = {
    case SendCliqueInfo(cliqueInfo) =>
      selfCliqueInfo = cliqueInfo

      IO(Udp) ! Udp.Bind(self, new InetSocketAddress(bindAddress.getPort))
      context become (binding orElse handleCommand)
  }

  def binding: Receive = {
    case Udp.Bound(address) =>
      log.debug(s"UDP server bound to $address")
      setSocket(ActorRefT[Udp.Command](sender()))
      log.debug(s"bootstrap nodes: ${bootstrap.mkString(";")}")
      bootstrap.foreach(tryPing)
      scheduleOnce(self, Scan, discoveryConfig.scanFastFrequency)
      context.become(ready)

    case Udp.CommandFailed(bind: Udp.Bind) =>
      log.error(s"Could not bind the UDP socket ($bind)")
      publishEvent(FlowMonitor.Shutdown)
  }

  def ready: Receive = handleData orElse handleCommand

  def handleData: Receive = {
    case Udp.Received(data, remote) =>
      DiscoveryMessage.deserialize(selfCliqueInfo.id, data, networkConfig.networkType) match {
        case Right(message: DiscoveryMessage) =>
          log.debug(s"Received ${message.payload.getClass.getSimpleName} from $remote")
          val sourceId = message.header.cliqueId
          updateStatus(sourceId)
          handlePayload(remote)(message.payload)
        case Left(error) =>
          // TODO: handler error properly
          log.debug(s"Received corrupted UDP data from $remote (${data.size} bytes): $error")
      }
  }

  def handleCommand: Receive = {
    case Scan =>
      log.debug(s"Scanning peers: $getPeersNum in total")
      cleanup()
      scan()
      if (shouldScanFast()) {
        scheduleOnce(self, Scan, discoveryConfig.scanFastFrequency)
      } else {
        scheduleOnce(self, Scan, discoveryConfig.scanFrequency)
      }
      ()
    case GetNeighborCliques =>
      sender() ! NeighborCliques(getActivePeers)
    case Disable(peerId) =>
      table -= peerId
      ()
  }

  def handlePayload(remote: InetSocketAddress)(payload: Payload): Unit =
    payload match {
      case Ping(cliqueInfoOpt) =>
        selfInterCliqueInfoOpt.foreach(info => send(remote, Pong(info)))
        cliqueInfoOpt.foreach(tryPing) // ping back when cliqueInfo is not empty
      case Pong(cliqueInfo) =>
        handlePong(cliqueInfo)
      case FindNode(targetId) =>
        val neighbors = getNeighbors(targetId)
        send(remote, Neighbors(neighbors))
      case Neighbors(peers) =>
        peers.foreach(tryPing)
    }

  override def publishNewClique(cliqueInfo: InterCliqueInfo): Unit = {
    publishEvent(NewClique(cliqueInfo))
  }
}
