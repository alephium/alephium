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
import scala.reflect.ClassTag

import akka.event.LoggingAdapter
import akka.testkit.{SocketUtil, TestProbe}
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.flow.network.udp.UdpServer
import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig, NetworkConfig}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AlephiumActorSpec, Duration}

class DiscoveryServerStateSpec
    extends AlephiumActorSpec("DiscoveryServer")
    with NoIndexModelGenerators {
  import DiscoveryMessage._
  import DiscoveryServerSpec._

  trait Fixture { self =>
    def groupSize: Int           = 4
    val udpPort: Int             = SocketUtil.temporaryLocalPort(udp = true)
    def peersPerGroup: Int       = 1
    def scanFrequency: Duration  = Duration.unsafe(500)
    def expireDuration: Duration = Duration.ofHoursUnsafe(1)
    val socketProbe              = TestProbe()
    val networkConfig            = new NetworkConfig { val networkType = NetworkType.Testnet }

    implicit lazy val config: DiscoveryConfig with BrokerConfig =
      createConfig(groupSize, udpPort, peersPerGroup, scanFrequency, expireDuration)._2

    lazy val state = new DiscoveryServerState {
      implicit def brokerConfig: BrokerConfig       = self.config
      implicit def discoveryConfig: DiscoveryConfig = self.config
      implicit def networkConfig: NetworkConfig     = self.networkConfig
      def log: LoggingAdapter                       = system.log

      lazy val bootstrap: ArraySeq[InetSocketAddress] = ArraySeq(socketAddressGen.sample.get)

      override def publishNewPeer(peer: BrokerInfo): Unit = ()

      lazy val selfCliqueInfo: CliqueInfo = cliqueInfoGen(config.groupNumPerBroker).sample.get

      setSocket(ActorRefT[UdpServer.Command](socketProbe.ref))
    }
    lazy val peerClique: CliqueInfo = cliqueInfoGen(config.groupNumPerBroker).sample.get
    lazy val peerInfo               = peerClique.interBrokers.get.sample()

    def expectPayload[T <: DiscoveryMessage.Payload: ClassTag]: Assertion = {
      val peerConfig =
        createConfig(groupSize, udpPort, peersPerGroup, scanFrequency)._2
      socketProbe.expectMsgPF() { case send: UdpServer.Send =>
        val message =
          DiscoveryMessage
            .deserialize(send.message, networkConfig.networkType)(
              peerConfig,
              peerConfig
            )
            .toOption
            .get
        message.payload is a[T]
      }
    }

    def addToTable(peerInfo: BrokerInfo): Assertion = {
      state.tryPing(peerInfo)
      state.isPending(peerInfo.peerId) is true
      state.handlePong(peerInfo)
      state.isInTable(peerInfo.peerId) is true
    }
  }

  it should "add peer into pending list when just pinged the peer" in new Fixture {
    state.getActivePeers.length is 0
    state.isUnknown(peerInfo.peerId) is true
    state.isPending(peerInfo.peerId) is false
    state.isPendingAvailable is true
    state.tryPing(peerInfo)
    expectPayload[Ping]
    state.isUnknown(peerInfo.peerId) is false
    state.isPending(peerInfo.peerId) is true
  }

  trait PingedFixture extends Fixture {
    state.tryPing(peerInfo)
    expectPayload[Ping]
    state.isInTable(peerInfo.peerId) is false
  }

  it should "remove peer from pending list when received pong back" in new PingedFixture {
    state.handlePong(peerInfo)
    expectPayload[FindNode]
    state.isUnknown(peerInfo.peerId) is false
    state.isPending(peerInfo.peerId) is false
    state.isInTable(peerInfo.peerId) is true
  }

  it should "clean up everything if timeout is zero" in new Fixture {
    override def scanFrequency: Duration  = Duration.unsafe(0)
    override def expireDuration: Duration = Duration.unsafe(0)

    addToTable(peerInfo)
    val peer0 = peerInfoGen.sample.get
    state.tryPing(peer0)
    state.isPending(peer0.peerId) is true
    state.cleanup()
    state.isInTable(peerInfo.peerId) is false
    state.isPending(peer0.peerId) is false
  }

  // TODO: use scalacheck
  it should "sort neighbors with respect to target" in new Fixture {
    override def peersPerGroup: Int = 4

    state.getActivePeers.length is 0
    val toAdds = Gen.listOfN(peersPerGroup, peerInfoGen).sample.get
    toAdds.foreach(addToTable)

    val peers0 = state.getNeighbors(peerInfo.cliqueId)
    peers0.length is peersPerGroup
    val bucket0 =
      peers0.map(p => peerInfo.cliqueId.hammingDist(p.cliqueId)).toIterable.toList
    bucket0 is bucket0.sorted
  }

  it should "clean up a banned peer" in new Fixture {
    addToTable(peerInfo)
    state.isInTable(peerInfo.peerId) is true

    state.banPeer(peerInfo.peerId)
    state.isInTable(peerInfo.peerId) is false
  }

  it should "clean up a banned peer from it's address" in new Fixture {
    addToTable(peerInfo)
    state.isInTable(peerInfo.peerId) is true

    state.banPeerFromAddress(peerInfo.address.getAddress)
    state.isInTable(peerInfo.peerId) is false
  }

  it should "remove peer" in new Fixture {
    addToTable(peerInfo)
    state.isInTable(peerInfo.peerId) is true

    state.remove(peerInfo.address)
    state.isInTable(peerInfo.peerId) is false
  }

  it should "not remove peers from self clique" in new Fixture {
    override def expireDuration: Duration = Duration.ofMillisUnsafe(0)
    config.expireDuration is Duration.unsafe(0)
    state.selfCliqueInfo.interBrokers.foreach { brokers =>
      brokers.foreach(state.addSelfCliquePeer)
    }
    state.selfCliqueInfo.interBrokers.foreach { brokers =>
      brokers.foreach(broker => state.isInTable(broker.peerId) is true)
    }
    state.cleanup()
    state.selfCliqueInfo.interBrokers.foreach { brokers =>
      brokers.foreach(broker => state.isInTable(broker.peerId) is true)
    }
  }

  it should "detect when to scan fast" in new Fixture {
    state.selfCliqueInfo.interBrokers.foreach { brokers =>
      brokers.foreach(state.addSelfCliquePeer)
    }
    state.tableInitialSize is state.getActivePeers.length
    state.shouldScanFast() is true
    state.atLeastOnePeerPerGroup() is false

    val clique0 = cliqueInfoGen.retryUntil(_.brokerNum > 1).sample.get
    state.appendPeer(
      clique0.interBrokers.get.filter(broker => !state.brokerConfig.intersect(broker)).head
    )
    state.shouldScanFast() is true
    state.atLeastOnePeerPerGroup() is false

    clique0.interBrokers.get.foreach(state.appendPeer)
    state.shouldScanFast() is true
    state.atLeastOnePeerPerGroup() is true
  }

  it should "ping discovered bootstrap nodes once" in new Fixture {
    state.scan()
    expectPayload[Ping]
    socketProbe.expectNoMessage()
    val peer = {
      val info = brokerInfoGen.sample.get
      BrokerInfo.unsafe(info.cliqueId, info.brokerId, info.groupNumPerBroker, state.bootstrap.head)
    }
    state.ping(peer)
    expectPayload[Ping]
    state.handlePong(peer)
    expectPayload[FindNode]
    state.scan()
    expectPayload[Ping]
    socketProbe.expectNoMessage()
  }
}
