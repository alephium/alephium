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

package org.alephium.flow.network.broker

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.Props
import akka.io.Tcp

import org.alephium.flow.network.{DiscoveryServer, TcpController}
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util._

// scalastyle:off number.of.methods
object MisbehaviorManager {
  def props(
      banDuration: Duration,
      penaltyForgiveness: Duration,
      penaltyFrequency: Duration
  ): Props =
    Props(new MisbehaviorManager(banDuration, penaltyForgiveness, penaltyFrequency))

  sealed trait Command
  final case class ConfirmConnection(connected: Tcp.Connected, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class ConfirmPeer(peerInfo: BrokerInfo) extends Command
  final case class GetPenalty(peer: InetAddress)     extends Command

  sealed trait Misbehavior extends Command with EventStream.Event {
    def remoteAddress: InetSocketAddress
    def penalty: Int
  }

  case object GetPeers                                extends Command
  final case class Unban(peers: AVector[InetAddress]) extends Command
  final case class Ban(peers: AVector[InetAddress])   extends Command

  final case class PeerBanned(remote: InetAddress) extends EventStream.Event

  final case class Peer(peer: InetAddress, status: MisbehaviorStatus)
  final case class Peers(peers: AVector[Peer])

  sealed trait Critical extends Misbehavior {
    def penalty: Int = Critical.penalty
  }
  object Critical {
    val penalty: Int = 100
  }
  sealed trait Error extends Misbehavior
  sealed trait Warning extends Misbehavior {
    def penalty: Int = Warning.penalty
  }
  object Warning {
    val penalty: Int = 20
  }
  sealed trait Uncertain extends Misbehavior {
    def penalty: Int = Uncertain.penalty
  }
  object Uncertain {
    val penalty: Int = 10
  }

  final case class InvalidFlowData(remoteAddress: InetSocketAddress)         extends Critical
  final case class InvalidPoW(remoteAddress: InetSocketAddress)              extends Critical
  final case class InvalidPingPongCritical(remoteAddress: InetSocketAddress) extends Critical
  final case class InvalidClientVersion(remoteAddress: InetSocketAddress)    extends Critical

  final case class Spamming(remoteAddress: InetSocketAddress)              extends Warning
  final case class InvalidFlowChainIndex(remoteAddress: InetSocketAddress) extends Warning
  final case class InvalidGroup(remoteAddress: InetSocketAddress)          extends Warning
  final case class SerdeError(remoteAddress: InetSocketAddress)            extends Warning

  final case class RequestTimeout(remoteAddress: InetSocketAddress)  extends Uncertain
  final case class InvalidPingPong(remoteAddress: InetSocketAddress) extends Uncertain
  final case class DeepForkBlock(remoteAddress: InetSocketAddress)   extends Uncertain

  sealed trait MisbehaviorStatus
  final case class Penalty(value: Int, timestamp: TimeStamp) extends MisbehaviorStatus
  final case class Banned(until: TimeStamp)                  extends MisbehaviorStatus
}
// scalastyle:on number.of.methods

class MisbehaviorManager(
    banDuration: Duration,
    penaltyForgiveness: Duration,
    penaltyFrequency: Duration
) extends BaseActor
    with EventStream {
  import MisbehaviorManager._

  private val misbehaviorThreshold: Int = Critical.penalty
  private val misbehaviorStorage: MisbehaviorStorage = new InMemoryMisbehaviorStorage(
    penaltyForgiveness
  )

  override def preStart(): Unit = {
    subscribeEvent(self, classOf[MisbehaviorManager.Misbehavior])
  }

  private def handleMisbehavior(misbehavior: Misbehavior): Unit = {
    val peer = misbehavior.remoteAddress.getAddress
    misbehaviorStorage.get(peer) match {
      case None => handlePenalty(peer, misbehavior.penalty)
      case Some(status) =>
        status match {
          case Banned(until) =>
            log.warning(s"${peer} already banned until $until, re-banning")
            banAndPublish(peer)
          case Penalty(current, lastTs) =>
            val newScore = current + misbehavior.penalty
            val tsDelta  = TimeStamp.now().deltaUnsafe(lastTs)
            if (tsDelta < penaltyFrequency && newScore < misbehaviorThreshold) {
              log.debug("Already penalized the peer recently, ignoring that misbehavior")
            } else {
              handlePenalty(peer, newScore)
            }
        }
    }
  }

  private def handlePenalty(peer: InetAddress, penalty: Int) = {
    if (penalty >= misbehaviorThreshold) {
      log.info(s"Ban $peer")
      banAndPublish(peer)
    } else {
      log.debug(s"Punish $peer, new penalty: $penalty")
      misbehaviorStorage.update(peer, Penalty(penalty, TimeStamp.now()))
    }
  }

  private def banAndPublish(peer: InetAddress) = {
    misbehaviorStorage.ban(peer, TimeStamp.now().plusMillisUnsafe(banDuration.millis))
    publishEvent(PeerBanned(peer))
  }

  override def receive: Receive = {
    case ConfirmConnection(connected, connection) =>
      if (misbehaviorStorage.isBanned(connected.remoteAddress.getAddress)) {
        sender() ! TcpController.ConnectionDenied(connected, connection)
      } else {
        sender() ! TcpController.ConnectionConfirmed(connected, connection)
      }

    case ConfirmPeer(peerInfo) =>
      if (misbehaviorStorage.isBanned(peerInfo.address.getAddress)) {
        sender() ! DiscoveryServer.PeerDenied(peerInfo)
      } else {
        sender() ! DiscoveryServer.PeerConfirmed(peerInfo)
      }

    case misbehavior: Misbehavior =>
      log.info(s"Misbehavior: $misbehavior")
      handleMisbehavior(misbehavior)

    case Unban(peers) =>
      peers.foreach(misbehaviorStorage.remove)

    case Ban(peers) =>
      peers.foreach(banAndPublish)

    case GetPeers =>
      sender() ! Peers(misbehaviorStorage.list())

    case GetPenalty(peer: InetAddress) =>
      val penalty = misbehaviorStorage.get(peer) match {
        case Some(_: Banned)         => misbehaviorThreshold
        case Some(Penalty(value, _)) => value
        case None                    => 0
      }
      sender() ! penalty
  }
}
