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
import scala.collection.mutable

import akka.event.LoggingAdapter
import akka.testkit.{SocketUtil, TestProbe}
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}

import org.alephium.flow.network.udp.UdpServer
import org.alephium.protocol.Generators
import org.alephium.protocol.config._
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AlephiumActorSpec, Duration, TimeStamp}

class DiscoveryServerStateSpec extends AlephiumActorSpec {
  import DiscoveryMessage._
  import DiscoveryServerSpec._

  trait Fixture extends NetworkConfigFixture.Default with Eventually with Generators { self =>
    def groupSize: Int           = 4
    val udpPort: Int             = SocketUtil.temporaryLocalPort(udp = true)
    def peersPerGroup: Int       = 1
    def scanFrequency: Duration  = Duration.unsafe(500)
    def expireDuration: Duration = Duration.ofHoursUnsafe(1)
    def peersTimeout: Duration   = Duration.ofSecondsUnsafe(5)
    def scanFastPeriod: Duration = Duration.ofMinutesUnsafe(1)
    val socketProbe              = TestProbe()

    implicit lazy val config: DiscoveryConfig with BrokerConfig =
      createConfig(
        groupSize,
        udpPort,
        peersPerGroup,
        scanFrequency,
        expireDuration,
        peersTimeout,
        scanFastPeriod
      )._2

    implicit lazy val groupConfig: GroupConfig = config

    implicit override val patienceConfig: PatienceConfig =
      PatienceConfig(timeout = Span(10, Seconds))

    val storage = mutable.HashMap.empty[PeerId, BrokerInfo]
    lazy val state = new DiscoveryServerState {
      implicit def brokerConfig: BrokerConfig       = self.config
      implicit def discoveryConfig: DiscoveryConfig = self.config
      implicit def networkConfig: NetworkConfig     = self.networkConfig
      def log: LoggingAdapter                       = system.log

      override def addBrokerToStorage(peerInfo: BrokerInfo): Unit =
        storage += peerInfo.peerId -> peerInfo
      override def removeBrokerFromStorage(peerId: PeerId): Unit =
        storage -= peerId

      lazy val bootstrap: ArraySeq[InetSocketAddress] = ArraySeq(socketAddressGen.sample.get)

      override def publishNewPeer(peer: BrokerInfo): Unit = ()

      lazy val selfCliqueInfo: CliqueInfo = cliqueInfoGen(config.groupNumPerBroker).sample.get

      setSocket(ActorRefT[UdpServer.Command](socketProbe.ref))
    }
    lazy val peerClique: CliqueInfo = cliqueInfoGen(config.groupNumPerBroker).sample.get
    lazy val peerInfo               = peerClique.interBrokers.get.sample()

    def expectPayload[T <: DiscoveryMessage.Payload]: T = {
      val peerConfig =
        createConfig(groupSize, udpPort, peersPerGroup, scanFrequency)._2
      socketProbe.expectMsgPF() { case send: UdpServer.Send =>
        val message =
          DiscoveryMessage.deserialize(send.message)(peerConfig, networkConfig).rightValue
        message.payload.asInstanceOf[T]
      }
    }

    def addToTable(peerInfo: BrokerInfo): Assertion = {
      state.tryPing(peerInfo)
      state.isPending(peerInfo.peerId) is true
      val ping = expectPayload[Ping]
      state.handlePong(ping.sessionId, peerInfo)
      expectPayload[FindNode]
      state.isInTable(peerInfo.peerId) is true
    }

    def cliqueWithSameIp(clique: CliqueInfo): CliqueInfo = {
      CliqueInfo.unsafe(
        cliqueIdGen.sample.get,
        clique.externalAddresses,
        clique.internalAddresses,
        clique.groupNumPerBroker,
        clique.priKey
      )
    }
  }

  it should "add peer into pending list when just pinged the peer" in new Fixture {
    state.getActivePeers().length is 0
    state.isUnknown(peerInfo.peerId) is true
    state.isPending(peerInfo.peerId) is false
    state.tryPing(peerInfo)
    expectPayload[Ping]
    state.isUnknown(peerInfo.peerId) is false
    state.isPending(peerInfo.peerId) is true
  }

  it should "append broker" in new Fixture {
    state.getActivePeers().length is 0

    groupConfig.groups is 4
    val cliqueGen = cliqueInfoGen(2)

    val peerClique0: CliqueInfo = cliqueGen.sample.get
    val clique0Brokers          = peerClique0.interBrokers.get
    clique0Brokers.length is 2
    state.discoveryConfig.maxCliqueFromSameIp is 2
    clique0Brokers.foreach(broker => state.appendPeer(broker))
    state.getActivePeers().length is 2
    clique0Brokers.foreach(broker => state.appendPeer(broker))
    state.getActivePeers().length is 2
    clique0Brokers.foreach(broker => storage.contains(broker.peerId) is true)

    val peerClique1: CliqueInfo = cliqueWithSameIp(peerClique0)
    val clique1Brokers          = peerClique1.interBrokers.get
    clique1Brokers.length is 2
    state.appendPeer(clique1Brokers(0))
    state.getActivePeers().length is 3
    state.isInTable(clique1Brokers(0).peerId) is true
    state.isInTable(clique1Brokers(1).peerId) is false
    storage.contains(clique1Brokers(0).peerId) is true
    storage.contains(clique1Brokers(1).peerId) is false

    val peerClique2: CliqueInfo = cliqueWithSameIp(peerClique0)
    val clique2Brokers          = peerClique2.interBrokers.get
    clique2Brokers.length is 2
    clique2Brokers.foreach(broker => state.appendPeer(broker))
    state.getActivePeers().length is 4
    state.isInTable(clique2Brokers(0).peerId) is false
    state.isInTable(clique2Brokers(1).peerId) is true
    storage.contains(clique2Brokers(0).peerId) is false
    storage.contains(clique2Brokers(1).peerId) is true
  }

  it should "get the number of cliques" in new Fixture {
    groupConfig.groups is 4
    val clique0        = cliqueInfoGen(2).sample.get
    val clique0Brokers = clique0.interBrokers.get
    val clique1 = CliqueInfo.unsafe(
      cliqueIdGen.sample.get,
      clique0.externalAddresses.init,
      clique0.internalAddresses.init,
      4,
      clique0.priKey
    )
    val clique1Brokers = clique1.interBrokers.get

    clique0Brokers.length is 2
    clique1Brokers.length is 1
    clique0Brokers.foreach(state.appendPeer)
    state.getActivePeers().length is 2
    state.getCliqueNumPerIp(clique1Brokers(0)) is 1
    clique1Brokers.foreach(state.appendPeer)
    state.getActivePeers().length is 3
    state.getCliqueNumPerIp(clique1Brokers(0)) is 2
  }

  trait PingedFixture extends Fixture {
    state.tryPing(peerInfo)
    val ping = expectPayload[Ping]
    state.isInTable(peerInfo.peerId) is false
    state.isPending(peerInfo.peerId) is true
    state.handlePong(ping.sessionId, peerInfo)
  }

  it should "remove peer from pending list when received pong back" in new PingedFixture {
    expectPayload[FindNode]
    state.isUnknown(peerInfo.peerId) is false
    state.isPending(peerInfo.peerId) is false
    state.isInTable(peerInfo.peerId) is true
  }

  it should "clean up table if expiry is zero" in new Fixture {
    override def expireDuration: Duration = Duration.ofSecondsUnsafe(0)
    addToTable(peerInfo)
    storage.contains(peerInfo.peerId) is true
    state.isInTable(peerInfo.peerId) is true
    state.cleanup()
    state.isInTable(peerInfo.peerId) is false
    storage.contains(peerInfo.peerId) is false
  }

  it should "clean up everything if timeout is zero" in new Fixture {
    override def peersTimeout: Duration = Duration.ofSecondsUnsafe(0)

    state.tryPing(peerInfo)
    state.isPending(peerInfo.peerId) is true
    state.cleanup()
    state.isPending(peerInfo.peerId) is false
  }

  it should "not ping pending peer" in new Fixture {
    val peer = peerInfoGen.sample.get
    state.tryPing(peer)
    expectPayload[Ping]
    state.tryPing(peer)
    socketProbe.expectNoMessage()
  }

  it should "sort neighbors with respect to target" in new Fixture {
    override def peersPerGroup: Int = 4

    state.getActivePeers().length is 0
    val toAdds = Gen.listOfN(peersPerGroup, peerInfoGen).sample.get
    toAdds.foreach(addToTable)

    val peers0 = state.getNeighbors(peerInfo.cliqueId)
    peers0.length is peersPerGroup
    val bucket0 =
      peers0.map(p => peerInfo.cliqueId.hammingDist(p.cliqueId)).toIterable.toList
    bucket0 is bucket0.sorted
  }

  it should "return neighbors" in new Fixture {
    state.getActivePeers().length is 0
    val toAdds = Gen.listOfN(peersPerGroup, peerInfoGen).sample.get
    toAdds.foreach(addToTable)

    state.getActivePeers().length is toAdds.length
    state.getMorePeers(peerInfo).foreach(_.intersect(peerInfo) is true)
    state.getMorePeers(peerInfo).foreach(_.cliqueId != state.selfCliqueId is true)
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

    val bannedUntil = state.unreachables.get(peerInfo.address.getAddress).get
    (bannedUntil > TimeStamp.now() + Duration.ofHoursUnsafe(12)) is true
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
      brokers.foreach(state.addBroker)
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
    override def scanFastPeriod: Duration = Duration.ofSecondsUnsafe(2)
    state.selfCliqueInfo.interBrokers.foreach { brokers =>
      brokers.foreach(state.addBroker)
    }
    state.tableInitialSize is state.getActivePeers().length
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
    eventually(state.shouldScanFast() is false)
  }

  it should "ping discovered bootstrap nodes once" in new Fixture {
    state.scan()
    expectPayload[Ping]
    socketProbe.expectNoMessage()
    val peer = {
      val info = brokerInfoGen.sample.get
      BrokerInfo.unsafe(info.cliqueId, info.brokerId, info.brokerNum, state.bootstrap.head)
    }
    state.ping(peer)
    val ping = expectPayload[Ping]
    state.handlePong(ping.sessionId, peer)
    expectPayload[FindNode]
    state.scan()
    expectPayload[Ping]
    socketProbe.expectNoMessage()
  }

  it should "not ping unreachable peer" in new Fixture {
    val peerInfo0 = Generators.peerInfoGen.sample.get
    val peerInfo1 = Generators.peerInfoGen.sample.get
    state.setUnreachable(peerInfo1.address)
    state.tryPing(peerInfo0)
    expectPayload[Ping]
    state.tryPing(peerInfo1)
    socketProbe.expectNoMessage()
  }

  it should "work for unreachables" in new Fixture {
    state.mightReachable(peerInfo.address) is true
    state.setUnreachable(peerInfo.address)
    state.mightReachable(peerInfo.address) is false
    state.unsetUnreachable(peerInfo.address.getAddress)
    state.mightReachable(peerInfo.address) is true

    state.setUnreachable(peerInfo.address)
    state.cleanUnreachables(TimeStamp.now())
    state.mightReachable(peerInfo.address) is false
    state.cleanUnreachables(TimeStamp.now().plusHoursUnsafe(1))
    state.mightReachable(peerInfo.address) is true
  }

  it should "set unreachables based on inet address" in new Fixture {
    state.getActivePeers().length is 0
    state.appendPeer(peerInfo)
    state.getActivePeers().length is 1
    state.mightReachable(peerInfo.address) is true

    val newPeer = new InetSocketAddress(peerInfo.address.getAddress, peerInfo.address.getPort + 1)
    state.setUnreachable(newPeer)
    state.mightReachable(peerInfo.address) is false
    state.getActivePeers().length is 0
  }

  it should "remove unreachable brokers based on inet address" in new Fixture {
    def createPeer(address: InetSocketAddress): BrokerInfo = {
      BrokerInfo.unsafe(
        cliqueIdGen.sample.get,
        brokerId = config.brokerId,
        brokerNum = config.brokerNum,
        address = address
      )
    }

    val address1  = socketAddressGen.sample.get
    val address2  = new InetSocketAddress(address1.getAddress, address1.getPort + 1)
    val peerInfo1 = createPeer(address1)
    val peerInfo2 = createPeer(address2)
    state.getActivePeers().length is 0
    state.appendPeer(peerInfo1)
    state.appendPeer(peerInfo2)
    state.getActivePeers().length is 2

    state.sessions.size is 0
    state.ping(peerInfo1)
    state.ping(peerInfo2)
    state.sessions.size is 2

    state.unreachables.size is 0
    state.setUnreachable(address1.getAddress)
    state.unreachables.size is 1
    state.sessions.size is 0
    state.getActivePeers().length is 0
  }

  it should "clean unreachable peers in mightReachableSlow" in new Fixture {
    val address = peerInfo.address.getAddress
    state.unreachables.put(peerInfo.address.getAddress, TimeStamp.now().plusSecondsUnsafe(2))
    state.mightReachable(peerInfo.address) is false
    state.mightReachableSlow(peerInfo.address) is false
    state.unreachables.put(peerInfo.address.getAddress, TimeStamp.zero)
    state.mightReachable(peerInfo.address) is false
    state.unreachables.contains(address) is true
    state.mightReachableSlow(peerInfo.address) is true
    state.unreachables.contains(address) is false
    state.mightReachable(peerInfo.address) is true
  }

  it should "correctly replace furthest peer when table is full" in new Fixture {
    while (
      state.getPeersWeight < state.brokerConfig.groups * state.discoveryConfig.neighborsPerGroup
    ) {
      addToTable(Generators.brokerInfoGen.sample.get)
    }
    val furthestPeer =
      state.getNeighbors(state.selfCliqueId).maxBy(_.cliqueId.hammingDist(state.selfCliqueId))
    val peer = Generators.brokerInfoGen
      .retryUntil(
        _.cliqueId.hammingDist(state.selfCliqueId) < state.selfCliqueId.hammingDist(
          furthestPeer.cliqueId
        )
      )
      .sample
      .get
    addToTable(peer)
    state.getNeighbors(state.selfCliqueId).contains(furthestPeer) is false
    state.getNeighbors(state.selfCliqueId).contains(peer) is true
  }
}
