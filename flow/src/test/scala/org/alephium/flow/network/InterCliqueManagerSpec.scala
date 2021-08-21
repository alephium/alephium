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

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

import akka.actor.ActorRef
import akka.io.Tcp
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import akka.util.Timeout
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.FlowFixture
import org.alephium.flow.handler.TestUtils
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.InterCliqueManager.SyncedResult
import org.alephium.flow.network.broker.{BrokerHandler, InboundConnection, OutboundConnection}
import org.alephium.protocol.{Generators, Hash}
import org.alephium.protocol.message.{Message, NewBlock, NewHeader}
import org.alephium.protocol.model.{BrokerInfo, ChainIndex}
import org.alephium.util._

class InterCliqueManagerSpec extends AlephiumActorSpec with Generators with ScalaFutures {
  override def actorSystemConfig = AlephiumActorSpec.debugConfig
  implicit val timeout: Timeout  = Timeout(Duration.ofSecondsUnsafe(2).asScala)

  it should "publish `PeerDisconnected` on inbound peer disconnection" in new Fixture {
    val connection = TestProbe()
    connection.send(interCliqueManager, Tcp.Connected(peer, socketAddressGen.sample.get))
    discoveryServer.expectMsg(DiscoveryServer.SendCliqueInfo(cliqueInfo))

    eventually {
      connection.send(interCliqueManager, CliqueManager.HandShaked(peerInfo, InboundConnection))
      getPeers() is Seq(peer)

      interCliqueManagerActor.brokers(peerInfo.peerId).actor.ref is connection.ref
      system.stop(connection.ref)
    }

    discoveryServer.expectMsg(DiscoveryServer.GetNeighborPeers(Some(brokerConfig)))

    getPeers() is Seq.empty
  }

  it should "publish `PeerDisconnected` on outbound peer disconnection" in new Fixture {
    EventFilter.info(start = "Try to connect to").intercept {
      interCliqueManager ! DiscoveryServer.NewPeer(peerInfo)
      interCliqueManager.underlyingActor.connecting.contains(peerInfo.address) is true
    }

    // It's already connecting, so there is no retry
    EventFilter.info(start = "Try to connect to", occurrences = 0).intercept {
      interCliqueManager ! DiscoveryServer.NewPeer(peerInfo)
    }

    EventFilter.info(start = "Peer disconnected:").intercept {
      val connection = getActor("*")(system.dispatcher).futureValue.get
      interCliqueManager.tell(CliqueManager.HandShaked(peerInfo, OutboundConnection), connection)
      getPeers() is Seq(peer)

      system.stop(connection)
    }

    discoveryServer.expectMsg(DiscoveryServer.SendCliqueInfo(cliqueInfo))
    discoveryServer.expectMsg(DiscoveryServer.GetNeighborPeers(Some(brokerConfig)))
    interCliqueManager.underlyingActor.connecting.contains(peerInfo.address) is false

    getPeers() is Seq.empty
  }

  it should "check for unknown incoming connections" in new Fixture {
    interCliqueManagerActor.checkForInConnection(0) is false
    interCliqueManagerActor.checkForInConnection(1) is true
  }

  it should "not include brokers that are not related to our groups" in new Fixture {
    val badBroker = irrelevantBrokerInfo()
    interCliqueManagerActor.checkForOutConnection(
      badBroker,
      maxOutboundConnectionsPerGroup
    ) is false
    EventFilter
      .warning(start = "New peer connection with invalid group info", occurrences = 1)
      .intercept {
        interCliqueManagerActor.handleNewBroker(badBroker, InboundConnection)
      }
  }

  it should "not re-add existing brokers" in new Fixture {
    val broker = relevantBrokerInfo()
    interCliqueManagerActor.addBroker(broker, OutboundConnection, ActorRefT(TestProbe().ref))
    EventFilter.debug(start = "Ignore another connection", occurrences = 1).intercept {
      interCliqueManagerActor.addBroker(broker, OutboundConnection, ActorRefT(TestProbe().ref))
    }
  }

  it should "not accept incoming connection when the number of inbound connections is large" in new Fixture {
    override val configValues = Map(
      ("alephium.network.max-inbound-connections-per-group", 1)
    )

    val broker = relevantBrokerInfo()
    EventFilter.warning(start = "Too many inbound connections", occurrences = 0).intercept {
      interCliqueManagerActor.handleNewBroker(broker, InboundConnection)
    }

    val newBroker = newBrokerInfo(broker)
    EventFilter.warning(start = "Too many inbound connections", occurrences = 1).intercept {
      val probe = TestProbe()
      watch(probe.ref)
      probe.send(interCliqueManager, CliqueManager.HandShaked(newBroker, InboundConnection))
      expectTerminated(probe.ref)
    }
  }

  it should "not accept outbound connection when the number of outbound connections is large" in new Fixture {
    override val configValues = Map(
      ("alephium.network.max-outbound-connections-per-group", 1)
    )

    val broker = relevantBrokerInfo()
    EventFilter.warning(start = "Too many outbound connections", occurrences = 0).intercept {
      interCliqueManagerActor.handleNewBroker(broker, OutboundConnection)
    }

    val newBroker = newBrokerInfo(broker)
    EventFilter.warning(start = "Too many outbound connections", occurrences = 1).intercept {
      val probe = TestProbe()
      watch(probe.ref)
      probe.send(interCliqueManager, CliqueManager.HandShaked(newBroker, OutboundConnection))
      expectTerminated(probe.ref)
    }
  }

  it should "deal with double connection (1)" in new Fixture {
    val broker = relevantBrokerInfo()

    val probe0 = TestProbe()
    watch(probe0.ref)
    probe0.send(interCliqueManager, CliqueManager.HandShaked(broker, InboundConnection))
    interCliqueManagerActor.brokers(broker.peerId).connectionType is InboundConnection

    val probe1 = TestProbe()
    watch(probe1.ref)
    probe1.send(interCliqueManager, CliqueManager.HandShaked(broker, OutboundConnection))

    if (cliqueInfo.id < broker.cliqueId) {
      // we should kill the inbound connection, and keep the outbound connection
      expectTerminated(probe0.ref)
      interCliqueManagerActor.brokers(broker.peerId).connectionType is OutboundConnection
    } else {
      // we should kill the inbound connection, and keep the inbound connection
      expectTerminated(probe1.ref)
      interCliqueManagerActor.brokers(broker.peerId).connectionType is InboundConnection
    }
  }

  it should "deal with double connection (2)" in new Fixture {
    val broker = relevantBrokerInfo()

    val probe0 = TestProbe()
    watch(probe0.ref)
    probe0.send(interCliqueManager, CliqueManager.HandShaked(broker, OutboundConnection))
    interCliqueManagerActor.brokers(broker.peerId).connectionType is OutboundConnection

    val probe1 = TestProbe()
    watch(probe1.ref)
    probe1.send(interCliqueManager, CliqueManager.HandShaked(broker, InboundConnection))

    if (cliqueInfo.id < broker.cliqueId) {
      // we should kill the inbound connection, and keep the outbound connection
      expectTerminated(probe1.ref)
      interCliqueManagerActor.brokers(broker.peerId).connectionType is OutboundConnection
    } else {
      // we should kill the inbound connection, and keep the inbound connection
      expectTerminated(probe0.ref)
      interCliqueManagerActor.brokers(broker.peerId).connectionType is InboundConnection
    }
  }

  behavior of "Extract peers"

  it should "not return self clique" in new Fixture {
    interCliqueManagerActor.extractPeersToConnect(cliqueInfo.interBrokers.get, 100).isEmpty is true
  }

  it should "not return already included peers" in new Fixture {
    val testBroker = relevantBrokerInfo()
    interCliqueManagerActor.addBroker(testBroker, OutboundConnection, ActorRefT(testActor))
    interCliqueManagerActor.extractPeersToConnect(AVector(testBroker), 100).isEmpty is true
  }

  it should "not return non-intersected peers" in new Fixture {
    val testBroker = irrelevantBrokerInfo()
    interCliqueManagerActor.extractPeersToConnect(AVector(testBroker), 100).isEmpty is true
  }

  it should "not return any peers when capacity is 0" in new Fixture {
    val testBroker = relevantBrokerInfo()
    interCliqueManagerActor.extractPeersToConnect(AVector(testBroker), 0).isEmpty is true
  }

  it should "return the peer when capacity is ok" in new Fixture {
    val testBroker = relevantBrokerInfo()
    interCliqueManagerActor.extractPeersToConnect(AVector(testBroker), 1) is AVector(testBroker)
  }

  behavior of "Sync"

  trait SyncFixture extends Fixture {
    def checkSynced(expected: Boolean) = {
      interCliqueManager ! InterCliqueManager.IsSynced
      expectMsg(InterCliqueManager.SyncedResult(expected))
    }

    def addAndCheckSynced(expected: Boolean) = {
      val broker = relevantBrokerInfo()
      interCliqueManager ! CliqueManager.HandShaked(broker, InboundConnection)
      interCliqueManager ! CliqueManager.Synced(broker)

      interCliqueManager ! InterCliqueManager.IsSynced
      eventually(expectMsg(InterCliqueManager.SyncedResult(expected)))
    }
  }

  it should "return synced for node when numBootstrapNodes is 0" in new SyncFixture {
    override lazy val numBootstrapNodes: Int = 0
    checkSynced(true)
  }

  it should "check if the node is synced when numBootstrapNodes is 1" in new SyncFixture {
    override lazy val numBootstrapNodes: Int = 1
    checkSynced(false)
    addAndCheckSynced(true)
  }

  it should "check if the node is synced when numBootstrapNodes is 2" in new SyncFixture {
    override lazy val numBootstrapNodes: Int = 2
    checkSynced(false)
    addAndCheckSynced(true)
  }

  it should "check if the node is synced when numBootstrapNodes is 3" in new SyncFixture {
    override lazy val numBootstrapNodes: Int = 3
    addAndCheckSynced(false)
    addAndCheckSynced(true)
  }

  it should "publish node synced status" in new SyncFixture {
    override val configValues = Map(("alephium.network.update-synced-frequency", "1 minute"))
    interCliqueManagerActor.lastNodeSyncedStatus is Some(false)

    def checkPublish(synced: Boolean) = {
      allHandlerProbes.blockHandlers.foreach(_._2.expectMsg(SyncedResult(synced)))
      allHandlerProbes.viewHandler.expectMsg(SyncedResult(synced))
    }

    def noPublish() = {
      allHandlerProbes.blockHandlers.foreach(_._2.expectNoMessage())
      allHandlerProbes.viewHandler.expectNoMessage()
    }

    override lazy val numBootstrapNodes: Int = 1
    checkSynced(false)
    checkPublish(false)
    interCliqueManagerActor.updateNodeSyncedStatus()
    interCliqueManagerActor.lastNodeSyncedStatus is Some(false)
    noPublish()

    addAndCheckSynced(true)
    interCliqueManagerActor.updateNodeSyncedStatus()
    interCliqueManagerActor.lastNodeSyncedStatus is Some(true)
    checkPublish(true)
    interCliqueManagerActor.updateNodeSyncedStatus()
    interCliqueManagerActor.lastNodeSyncedStatus is Some(true)
    noPublish()
  }

  it should "send block to peers" in new BroadCastFixture {
    val brokerInfo0 = genBrokerInfo(0)
    val brokerInfo1 = genBrokerInfo(2)
    val brokerInfo2 = genBrokerInfo(0)
    val brokerInfo3 = genBrokerInfo(1)
    val broker0     = TestProbe()
    val broker1     = TestProbe()
    val broker2     = TestProbe()
    val broker3     = TestProbe()

    broker0.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo0, InboundConnection))
    broker1.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo1, InboundConnection))
    broker2.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo2, InboundConnection))
    broker3.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo3, InboundConnection))
    interCliqueManagerActor.brokers.contains(brokerInfo0.peerId) is true
    interCliqueManagerActor.brokers.contains(brokerInfo1.peerId) is true
    interCliqueManagerActor.brokers.contains(brokerInfo2.peerId) is true
    interCliqueManagerActor.brokers.contains(brokerInfo3.peerId) is false

    interCliqueManager ! CliqueManager.Synced(brokerInfo0)
    interCliqueManager ! CliqueManager.Synced(brokerInfo1)

    val message = genBroadCastBlock(ChainIndex.unsafe(0, 1), DataOrigin.Local)
    interCliqueManager ! message
    broker0.expectMsg(BrokerHandler.Send(message.blockMsg))
    broker1.expectNoMessage()
    broker2.expectNoMessage()
  }

  it should "send block announcements to peers" in new BroadCastFixture {
    val brokerInfo0 = genBrokerInfo(0)
    val brokerInfo1 = genBrokerInfo(2)
    val brokerInfo2 = genBrokerInfo(0)
    val brokerInfo3 = genBrokerInfo(0)
    val broker0     = TestProbe()
    val broker1     = TestProbe()
    val broker2     = TestProbe()
    val broker3     = TestProbe()

    broker0.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo0, InboundConnection))
    broker1.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo1, InboundConnection))
    broker2.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo2, InboundConnection))
    broker3.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo3, InboundConnection))
    interCliqueManagerActor.brokers.contains(brokerInfo0.peerId) is true
    interCliqueManagerActor.brokers.contains(brokerInfo1.peerId) is true
    interCliqueManagerActor.brokers.contains(brokerInfo2.peerId) is true
    interCliqueManagerActor.brokers.contains(brokerInfo3.peerId) is true

    interCliqueManager ! CliqueManager.Synced(brokerInfo0)
    interCliqueManager ! CliqueManager.Synced(brokerInfo1)
    interCliqueManager ! CliqueManager.Synced(brokerInfo2)

    val message = genBroadCastBlock(ChainIndex.unsafe(0, 1), DataOrigin.InterClique(brokerInfo0))
    interCliqueManager ! message
    broker0.expectNoMessage()
    broker1.expectNoMessage()
    broker2.expectMsg(BrokerHandler.RelayBlock(message.block.hash))
    broker3.expectNoMessage()
  }

  it should "send tx announcements to peers" in new BroadCastFixture {
    val brokerInfo0 = genBrokerInfo(0)
    val brokerInfo1 = genBrokerInfo(2)
    val brokerInfo2 = genBrokerInfo(0)
    val brokerInfo3 = genBrokerInfo(0)
    val broker0     = TestProbe()
    val broker1     = TestProbe()
    val broker2     = TestProbe()
    val broker3     = TestProbe()

    broker0.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo0, InboundConnection))
    broker1.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo1, InboundConnection))
    broker2.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo2, InboundConnection))
    broker3.send(interCliqueManager, CliqueManager.HandShaked(brokerInfo3, InboundConnection))
    interCliqueManagerActor.brokers.contains(brokerInfo0.peerId) is true
    interCliqueManagerActor.brokers.contains(brokerInfo1.peerId) is true
    interCliqueManagerActor.brokers.contains(brokerInfo2.peerId) is true
    interCliqueManagerActor.brokers.contains(brokerInfo3.peerId) is true

    interCliqueManager ! CliqueManager.Synced(brokerInfo0)
    interCliqueManager ! CliqueManager.Synced(brokerInfo1)
    interCliqueManager ! CliqueManager.Synced(brokerInfo2)

    val txHashes   = AVector.fill(4)(Hash.generate)
    val chainIndex = ChainIndex.unsafe(0, 1)
    val message =
      CliqueManager.BroadCastTx(txHashes, chainIndex, DataOrigin.InterClique(brokerInfo0))
    interCliqueManager ! message
    broker0.expectNoMessage()
    broker1.expectNoMessage()
    broker2.expectMsg(BrokerHandler.RelayTxs(AVector((chainIndex, txHashes))))
    broker3.expectNoMessage()
  }

  trait BroadCastFixture extends Fixture {
    override val configValues: Map[String, Any] = Map(
      "alephium.broker.groups"     -> 4,
      "alephium.broker.broker-num" -> 2,
      "alephium.broker.broker-id"  -> 0
    )

    def genBrokerInfo(brokerId: Int): BrokerInfo = {
      BrokerInfo.unsafe(
        Generators.cliqueIdGen.sample.get,
        brokerId = brokerId,
        brokerNum = groups0,
        Generators.socketAddressGen.sample.get
      )
    }

    def genBroadCastBlock(
        chainIndex: ChainIndex,
        origin: DataOrigin
    ): CliqueManager.BroadCastBlock = {
      val block = emptyBlock(blockFlow, chainIndex)
      CliqueManager.BroadCastBlock(
        block,
        Message.serialize(NewBlock(block)),
        Message.serialize(NewHeader(block.header)),
        origin,
        broadcastInterClique = true
      )
    }
  }

  trait Fixture extends FlowFixture with Generators {
    lazy val maxOutboundConnectionsPerGroup: Int = config.network.maxOutboundConnectionsPerGroup
    lazy val maxInboundConnectionsPerGroup: Int  = config.network.maxInboundConnectionsPerGroup

    lazy val cliqueInfo = cliqueInfoGen.sample.get

    lazy val discoveryServer                 = TestProbe()
    lazy val blockFlowSynchronizer           = TestProbe()
    lazy val (allHandlers, allHandlerProbes) = TestUtils.createAllHandlersProbe

    lazy val parentName        = s"InterCliqueManager-${Random.nextInt()}"
    lazy val numBootstrapNodes = 1
    val syncProbe              = TestProbe()
    system.eventStream.subscribe(syncProbe.ref, classOf[SyncedResult])
    lazy val interCliqueManager = TestActorRef[InterCliqueManager](
      InterCliqueManager.props(
        cliqueInfo,
        blockFlow,
        allHandlers,
        ActorRefT(discoveryServer.ref),
        ActorRefT(blockFlowSynchronizer.ref),
        numBootstrapNodes
      ),
      parentName
    )
    lazy val interCliqueManagerActor = interCliqueManager.underlyingActor

    lazy val peer = socketAddressGen.sample.get

    lazy val peerInfo = BrokerInfo.unsafe(
      cliqueIdGen.sample.get,
      brokerConfig.brokerId,
      cliqueInfo.groupNumPerBroker,
      peer
    )

    def getActor(
        name: String
    )(implicit executionContext: ExecutionContext): Future[Option[ActorRef]] =
      system
        .actorSelection(s"user/$parentName/$name")
        .resolveOne()
        .map(Some(_))
        .recover(_ => None)

    def getPeers() =
      interCliqueManager
        .ask(InterCliqueManager.GetSyncStatuses)
        .mapTo[Seq[InterCliqueManager.SyncStatus]]
        .futureValue
        .map(_.address)

    def irrelevantBrokerInfo(): BrokerInfo = {
      brokerInfoGen.retryUntil(!_.intersect(brokerConfig)).sample.get
    }

    def relevantBrokerInfo(): BrokerInfo = {
      brokerInfoGen.retryUntil(_.intersect(brokerConfig)).sample.get
    }

    def newBrokerInfo(info: BrokerInfo): BrokerInfo = {
      BrokerInfo.unsafe(
        cliqueIdGen.sample.get,
        info.brokerId,
        info.brokerNum,
        socketAddressGen.sample.get
      )
    }
  }
}
