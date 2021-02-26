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

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.Tcp

import org.alephium.flow.network.{DiscoveryServer, TcpController}
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util._

object MisbehaviorManager {
  def props(banDuration: Duration): Props =
    Props(new MisbehaviorManager(banDuration))

  sealed trait Command
  final case class ConfirmConnection(connected: Tcp.Connected, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class ConfirmPeer(peerInfo: BrokerInfo) extends Command

  sealed trait Misbehavior extends Command with EventStream.Event {
    def remoteAddress: InetSocketAddress
    def penalty: Int
  }

  case object GetPeers extends Command

  final case class PeerBanned(remote: InetSocketAddress) extends EventStream.Event

  final case class Peer(peer: InetSocketAddress, status: MisbehaviorStatus)
  final case class Peers(peers: AVector[Peer])

  sealed trait Critical  extends Misbehavior
  sealed trait Error     extends Misbehavior
  sealed trait Warning   extends Misbehavior
  sealed trait Uncertain extends Misbehavior

  final case class InvalidMessage(remoteAddress: InetSocketAddress) extends Critical {
    val penalty: Int = 100
  }

  final case class InvalidPingPong(remoteAddress: InetSocketAddress) extends Critical {
    val penalty: Int = 100
  }

  final case class InvalidDag(remoteAddress: InetSocketAddress) extends Critical {
    val penalty: Int = 100
  }

  final case class Spamming(remoteAddress: InetSocketAddress) extends Warning {
    val penalty: Int = 20
  }

  final case class RequestTimeout(remoteAddress: InetSocketAddress) extends Uncertain {
    val penalty: Int = 10
  }

  sealed trait MisbehaviorStatus
  final case class Penalty(value: Int)      extends MisbehaviorStatus
  final case class Banned(until: TimeStamp) extends MisbehaviorStatus
}

class MisbehaviorManager(banDuration: Duration) extends BaseActor with EventStream {
  import MisbehaviorManager._

  private val misbehaviorThreshold: Int              = 100
  private val misbehaviorStorage: MisbehaviorStorage = new InMemoryMisbehaviorStorage()

  override def preStart(): Unit = {
    subscribeEvent(self, classOf[MisbehaviorManager.Misbehavior])
  }

  private def handleMisbehavior(misbehavior: Misbehavior): Unit = {
    val peer = misbehavior.remoteAddress
    misbehaviorStorage.get(peer) match {
      case None => handlePenalty(peer, misbehavior.penalty)
      case Some(status) =>
        status match {
          case Banned(until) =>
            log.warning(s"${peer} already banned until $until, re-banning")
            banAndPublish(peer)
          case Penalty(current) =>
            val newScore = current + misbehavior.penalty
            handlePenalty(peer, newScore)
        }

    }
  }

  private def handlePenalty(peer: InetSocketAddress, penalty: Int) = {
    if (penalty >= misbehaviorThreshold) {
      log.debug(s"Ban $peer")
      banAndPublish(peer)
    } else {
      log.debug(s"Punish $peer, new penalty: $penalty")
      misbehaviorStorage.update(peer, Penalty(penalty))
    }
  }

  private def banAndPublish(peer: InetSocketAddress) = {
    log.info(s"Banning peer: $peer")
    misbehaviorStorage.ban(peer, TimeStamp.now().plusMillisUnsafe(banDuration.millis))
    publishEvent(PeerBanned(peer))
  }

  override def receive: Receive = {
    case ConfirmConnection(connected, connection) =>
      if (misbehaviorStorage.isBanned(connected.remoteAddress)) {
        sender() ! TcpController.ConnectionDenied(connected, connection)
      } else {
        sender() ! TcpController.ConnectionConfirmed(connected, connection)
      }

    case ConfirmPeer(peerInfo) =>
      if (misbehaviorStorage.isBanned(peerInfo.address)) {
        sender() ! DiscoveryServer.PeerDenied(peerInfo)
      } else {
        sender() ! DiscoveryServer.PeerConfirmed(peerInfo)
      }

    case misbehavior: Misbehavior =>
      log.debug(s"Misbehavior: $misbehavior")
      handleMisbehavior(misbehavior)
    case GetPeers =>
      sender() ! Peers(misbehaviorStorage.list())
  }
}
