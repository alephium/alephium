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

import akka.io.Tcp
import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.network.DiscoveryServer
import org.alephium.flow.network.TcpController
import org.alephium.flow.network.broker.MisbehaviorManager._
import org.alephium.protocol.Generators
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util.{AVector, Duration, TimeStamp}

class MisbehaviorManagerSpec extends AlephiumFlowActorSpec {
  it should "start without peers" in new Fixture {
    misbehaviorManager ! GetPeers
    expectMsg(Peers(AVector.empty))
  }

  it should "penalize peer" in new Fixture {
    misbehaviorManager ! Spamming(peer)
    misbehaviorManager ! GetPeers
    expectMsgPF() { case Peers(peers) =>
      val Peer(address, Penalty(value, _)) = peers.head
      address is peer.getAddress
      value is 20
    }
  }

  it should "confirm known peer" in new Fixture {
    misbehaviorManager ! Spamming(peer)
    misbehaviorManager ! ConfirmConnection(connected, connection.ref)
    expectMsg(TcpController.ConnectionConfirmed(connected, connection.ref))
  }

  it should "confirm unknown peer" in new Fixture {
    misbehaviorManager ! ConfirmConnection(connected, connection.ref)
    expectMsg(TcpController.ConnectionConfirmed(connected, connection.ref))
  }

  it should "ban and refuse peer that misbehaved " in new Fixture {
    misbehaviorManager ! Spamming(peer)
    misbehaviorManager ! GetPeers
    expectMsgPF() { case Peers(peers) =>
      val Peer(address, Penalty(value, _)) = peers.head
      address is peer.getAddress
      value is 20
    }

    misbehaviorManager ! Spamming(peer)
    misbehaviorManager ! Spamming(peer)
    misbehaviorManager ! Spamming(peer)
    misbehaviorManager ! Spamming(peer)

    bannedProbe.expectMsg(PeerBanned(peer.getAddress))

    misbehaviorManager ! ConfirmConnection(connected, connection.ref)
    expectMsg(TcpController.ConnectionDenied(connected, connection.ref))

    misbehaviorManager ! GetPeers
    expectMsgPF() { case Peers(peers) =>
      peers.map {
        case Peer(peerToTest, Banned(until)) =>
          peerToTest is peer.getAddress
          TimeStamp.now().isBefore(until) is true
        case peer => throw new AssertionError(s"Wrong peer: $peer")

      }
    }
  }

  it should "unban after a given amount of time" in new Fixture {
    override val banDuration = Duration.zero

    misbehaviorManager ! InvalidFlowData(peer)
    bannedProbe.expectMsg(PeerBanned(peer.getAddress))

    eventually {
      misbehaviorManager ! GetPeers
      expectMsg(Peers(AVector.empty))
    }
  }

  it should "manually unban" in new Fixture {
    misbehaviorManager ! InvalidFlowData(peer)

    bannedProbe.expectMsg(PeerBanned(peer.getAddress))

    misbehaviorManager ! GetPeers
    expectMsgPF() { case Peers(peers) =>
      peers.nonEmpty is true
    }

    misbehaviorManager ! Unban(AVector(peer.getAddress))

    misbehaviorManager ! GetPeers
    expectMsg(Peers(AVector.empty))
  }

  it should "manually ban" in new Fixture {
    misbehaviorManager ! Ban(AVector(peer.getAddress))
    bannedProbe.expectMsg(PeerBanned(peer.getAddress))

    misbehaviorManager ! GetPeers
    expectMsgPF() { case Peers(peers) =>
      peers.nonEmpty is true
    }
  }

  it should "forgive a penalty after some time" in new Fixture {
    override val penaltyForgiveness = Duration.zero

    misbehaviorManager ! Spamming(peer)

    eventually {
      misbehaviorManager ! GetPeers
      expectMsg(Peers(AVector.empty))
    }
  }

  it should "not penalize too fast" in new Fixture {
    override val penaltyFrequency = Duration.ofHoursUnsafe(1)

    misbehaviorManager ! Spamming(peer)
    misbehaviorManager ! GetPeers
    expectMsgPF() { case Peers(peers) =>
      val Peer(address, Penalty(value, _)) = peers.head
      address is peer.getAddress
      value is 20
    }

    misbehaviorManager ! Spamming(peer)
    misbehaviorManager ! GetPeers
    expectMsgPF() { case Peers(peers) =>
      val Peer(address, Penalty(value, _)) = peers.head
      address is peer.getAddress
      value is 20
    }

    misbehaviorManager ! InvalidPoW(peer)
    misbehaviorManager ! GetPeers
    expectMsgPF() { case Peers(peers) =>
      peers.head.status is a[Banned]
    }
    bannedProbe.expectMsg(PeerBanned(peer.getAddress))
  }

  it should "re-ban peer" in new Fixture {
    def getBanUntil(): TimeStamp = {
      misbehaviorManager ! GetPeers
      expectMsgPF() { case Peers(peers) =>
        peers.head.peer is peer.getAddress
        peers.head.status is a[Banned]
        peers.head.status.asInstanceOf[Banned].until
      }
    }

    misbehaviorManager ! InvalidPoW(peer)
    bannedProbe.expectMsg(PeerBanned(peer.getAddress))
    val until1 = getBanUntil()

    Thread.sleep(100)
    misbehaviorManager ! Spamming(peer)
    bannedProbe.expectMsg(PeerBanned(peer.getAddress))
    val until2 = getBanUntil()
    until1.isBefore(until2) is true
  }

  it should "not confirm banned peer" in new Fixture {
    misbehaviorManager ! InvalidFlowData(peer)
    val brokerInfo = BrokerInfo.unsafe(
      Generators.cliqueIdGen.sample.get,
      1,
      config.broker.brokerNum,
      peer
    )
    misbehaviorManager ! ConfirmPeer(brokerInfo)
    expectMsg(DiscoveryServer.PeerDenied(brokerInfo))
  }

  trait Fixture extends Generators {
    val banDuration        = Duration.ofHoursUnsafe(1)
    val penaltyForgiveness = Duration.ofHoursUnsafe(1)
    val penaltyFrequency   = Duration.zero

    lazy val misbehaviorManager =
      system.actorOf(MisbehaviorManager.props(banDuration, penaltyForgiveness, penaltyFrequency))

    val peer       = socketAddressGen.sample.get
    val local      = socketAddressGen.sample.get
    val connection = TestProbe()
    val connected  = Tcp.Connected(peer, local)

    val bannedProbe = TestProbe()
    system.eventStream.subscribe(bannedProbe.ref, classOf[MisbehaviorManager.PeerBanned])
  }
}
