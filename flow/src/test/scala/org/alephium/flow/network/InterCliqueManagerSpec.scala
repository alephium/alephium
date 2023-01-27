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

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.io.Tcp
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.FlowFixture
import org.alephium.flow.handler.TestUtils
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.InterCliqueManager.{BrokerState, SyncedResult}
import org.alephium.flow.network.broker._
import org.alephium.protocol.Generators
import org.alephium.protocol.message.{Message, NewBlock}
import org.alephium.protocol.model.{BrokerInfo, ChainIndex, TransactionId}
import org.alephium.util._

class InterCliqueManagerSpec extends AlephiumActorSpec with Generators with ScalaFutures {
  override def actorSystemConfig = AlephiumActorSpec.debugConfig
  implicit val timeout: Timeout  = Timeout(Duration.ofSecondsUnsafe(2).asScala)
  val clientInfo: String         = "v0.0.0"

  def publishHandShaked(
      broker: ActorRef,
      brokerInfo: BrokerInfo,
      connectionType: ConnectionType = InboundConnection
  )(implicit system: ActorSystem): Unit = {
    val event = InterCliqueManager.HandShaked(broker, brokerInfo, connectionType, clientInfo)
    system.eventStream.publish(event)
  }

  def publishHandShakedForState(
      broker: BrokerState,
      connectionType: ConnectionType = InboundConnection
  )(implicit system: ActorSystem): Unit = {
    publishHandShaked(broker.actor.ref, broker.info, connectionType)
  }

  it should "publish `PeerDisconnected` on inbound peer disconnection" in new Fixture {
    val connection = TestProbe()
    connection.send(interCliqueManager, Tcp.Connected(peer, socketAddressGen.sample.get))
    discoveryServer.expectMsg(DiscoveryServer.GetMorePeers(brokerConfig))
    discoveryServer.expectMsg(DiscoveryServer.SendCliqueInfo(cliqueInfo))

    publishHandShaked(connection.ref, peerInfo)
    eventually {
      getPeers() is Seq(peer)

      interCliqueManagerActor.brokers(peerInfo.peerId).actor.ref is connection.ref
      system.stop(connection.ref)
    }

    discoveryServer.expectMsg(DiscoveryServer.GetMorePeers(brokerConfig))

    getPeers() is Seq.empty
  }

  it should "publish `PeerDisconnected` on outbound peer disconnection" in new Fixture {
    EventFilter.info(start = "Try to connect to").intercept {
      interCliqueManager ! DiscoveryServer.NewPeer(peerInfo)
      interCliqueManagerActor.connecting.contains(peerInfo.address) is true
      discoveryServer.expectMsg(DiscoveryServer.GetMorePeers(brokerConfig))
    }

    // It's already connecting, so there is no retry
    EventFilter.info(start = "Try to connect to", occurrences = 0).intercept {
      interCliqueManager ! DiscoveryServer.NewPeer(peerInfo)
    }

    EventFilter.info(start = "Peer disconnected:").intercept {
      val connection = getActor("*")(system.dispatcher).futureValue.get
      publishHandShaked(connection, peerInfo, OutboundConnection)
      getPeers() willBe Seq(peer)

      system.stop(connection)
    }

    discoveryServer.expectMsg(DiscoveryServer.SendCliqueInfo(cliqueInfo))
    discoveryServer.expectMsg(DiscoveryServer.GetMorePeers(brokerConfig))
    interCliqueManagerActor.connecting.contains(peerInfo.address) is false

    getPeers() is Seq.empty
  }

  it should "check for unknown incoming connections" in new Fixture {
    interCliqueManagerActor.checkForInConnection(0) is false
    interCliqueManagerActor.checkForInConnection(1) is true
  }

  it should "not accept too many connections from a same IP" in new Fixture {
    val broker0 = relevantBrokerInfo().info
    val broker1 = BrokerInfo.unsafe(
      cliqueIdGen.sample.get,
      broker0.brokerId,
      broker0.brokerNum,
      broker0.address
    )
    val broker2 = BrokerInfo.unsafe(
      cliqueIdGen.sample.get,
      broker0.brokerId,
      broker0.brokerNum,
      broker0.address
    )
    config.network.maxCliqueFromSameIp is 2
    val connection = TestProbe().ref
    watch(connection)
    interCliqueManagerActor.brokers.size willBe 0 // unlazy the actor
    publishHandShaked(connection, broker0, InboundConnection)
    interCliqueManagerActor.brokers.size willBe 1
    publishHandShaked(connection, broker1, InboundConnection)
    interCliqueManagerActor.brokers.size willBe 2
    EventFilter.debug(start = "Too many clique connection from the same IP").intercept {
      publishHandShaked(connection, broker2, InboundConnection)
    }
    interCliqueManagerActor.brokers.size willBe 2
    expectTerminated(connection)
  }

  it should "not include brokers that are not related to our groups" in new Fixture {
    val badBroker = irrelevantBrokerInfo()
    interCliqueManagerActor.checkForOutConnection(
      badBroker.info,
      maxOutboundConnectionsPerGroup
    ) is false
    EventFilter
      .warning(start = "New peer connection with invalid group info", occurrences = 1)
      .intercept {
        interCliqueManagerActor.handleNewBroker(badBroker)
      }
  }

  it should "not re-add existing brokers" in new Fixture {
    val broker = relevantBrokerInfo()
    interCliqueManagerActor.addBroker(broker)
    EventFilter.debug(start = "Ignore another connection", occurrences = 1).intercept {
      interCliqueManagerActor.addBroker(broker)
    }
  }

  it should "not accept incoming connection when the number of inbound connections is large" in new Fixture {
    override val configValues = Map(
      ("alephium.network.max-inbound-connections-per-group", 1)
    )

    val broker = relevantBrokerInfo()
    EventFilter.info(start = "Too many inbound connections", occurrences = 0).intercept {
      interCliqueManagerActor.handleNewBroker(broker)
      interCliqueManagerActor.brokers.size willBe 1
    }

    val newBroker = newBrokerInfo(broker.info)
    EventFilter.info(start = "Too many inbound connections", occurrences = 1).intercept {
      val probe = TestProbe()
      watch(probe.ref)
      publishHandShaked(probe.ref, newBroker, InboundConnection)
      expectTerminated(probe.ref)
      interCliqueManagerActor.brokers.size willBe 1
    }
  }

  it should "not accept outbound connection when the number of outbound connections is large" in new Fixture {
    override val configValues = Map(
      ("alephium.network.max-outbound-connections-per-group", 1)
    )

    val broker = relevantBrokerInfo(OutboundConnection)
    EventFilter.info(start = "Too many outbound connections", occurrences = 0).intercept {
      interCliqueManagerActor.handleNewBroker(broker)
    }

    val newBroker = newBrokerInfo(broker.info)
    EventFilter.info(start = "Too many outbound connections", occurrences = 1).intercept {
      val probe = TestProbe()
      watch(probe.ref)
      publishHandShaked(probe.ref, newBroker, OutboundConnection)
      expectTerminated(probe.ref)
    }
  }

  it should "not accept outbound connection when the number of pending outbound connections is large" in new Fixture {
    override val configValues = Map(
      ("alephium.network.max-outbound-connections-per-group", 1)
    )

    val broker = relevantBrokerInfo()
    interCliqueManagerActor.connecting.put(broker.info.address, broker.info)

    val newBroker = newBrokerInfo(broker.info)
    EventFilter.info(start = "Too many outbound connections", occurrences = 1).intercept {
      val probe = TestProbe()
      watch(probe.ref)
      publishHandShaked(probe.ref, newBroker, OutboundConnection)
      expectTerminated(probe.ref)
    }
  }

  it should "not start outbound connection when the number of pending outbound connections is large" in new Fixture {
    override val configValues = Map(
      ("alephium.network.max-outbound-connections-per-group", 1)
    )

    val broker = relevantBrokerInfo().info
    interCliqueManagerActor.connecting.put(broker.address, broker)

    val newBroker = newBrokerInfo(broker)
    EventFilter.info(start = "Try to connect to", occurrences = 0).intercept {
      interCliqueManager ! DiscoveryServer.NeighborPeers(AVector(newBroker))
    }
  }

  trait DoubleConnectionFixture extends Fixture {
    val broker   = relevantBrokerInfo()
    val probe0   = TestProbe()
    val probe1   = TestProbe()
    val listener = TestProbe()

    watch(probe0.ref)
    watch(probe1.ref)
    system.eventStream.subscribe(listener.ref, classOf[DiscoveryServer.Unreachable])

    interCliqueManagerActor.context.watchWith(
      probe0.ref,
      InterCliqueManager.PeerDisconnected(broker.info.address)
    )
    interCliqueManagerActor.context.watchWith(
      probe1.ref,
      InterCliqueManager.PeerDisconnected(broker.info.address)
    )
  }

  it should "deal with double connection (1)" in new DoubleConnectionFixture {
    publishHandShakedForState(broker.copy(actor = probe0.ref), InboundConnection)
    interCliqueManagerActor.brokers(broker.info.peerId).connectionType willBe InboundConnection

    publishHandShakedForState(broker.copy(actor = probe1.ref), OutboundConnection)

    if (cliqueInfo.id < broker.info.cliqueId) {
      // we should kill the inbound connection, and keep the outbound connection
      expectTerminated(probe0.ref)
      interCliqueManagerActor.brokers(broker.info.peerId).connectionType is OutboundConnection
    } else {
      // we should kill the outbound connection, and keep the inbound connection
      expectTerminated(probe1.ref)
      interCliqueManagerActor.brokers(broker.info.peerId).connectionType is InboundConnection
    }
    listener.expectNoMessage()
  }

  it should "deal with double connection (2)" in new DoubleConnectionFixture {
    interCliqueManagerActor.brokers.isEmpty is true // unlazy the actor
    publishHandShakedForState(broker.copy(actor = probe0.ref), OutboundConnection)
    interCliqueManagerActor.brokers(broker.info.peerId).connectionType willBe OutboundConnection

    publishHandShakedForState(broker.copy(actor = probe1.ref), InboundConnection)

    if (cliqueInfo.id < broker.info.cliqueId) {
      // we should kill the inbound connection, and keep the outbound connection
      expectTerminated(probe1.ref)
      interCliqueManagerActor.brokers(broker.info.peerId).connectionType is OutboundConnection
    } else {
      // we should kill the outbound connection, and keep the inbound connection
      expectTerminated(probe0.ref)
      interCliqueManagerActor.brokers(broker.info.peerId).connectionType is InboundConnection
    }
    listener.expectNoMessage()
  }

  it should "close the double connections when they are of the same type" in new Fixture {
    interCliqueManagerActor.brokers.isEmpty is true // unlazy the actor

    val broker = relevantBrokerInfo()
    val probe0 = TestProbe()
    watch(probe0.ref)
    publishHandShakedForState(broker.copy(actor = probe0.ref), InboundConnection)
    interCliqueManagerActor.brokers(broker.info.peerId).connectionType willBe InboundConnection

    val probe1 = TestProbe()
    EventFilter.debug(start = "Invalid double connection").intercept {
      watch(probe1.ref)
      publishHandShakedForState(broker.copy(actor = probe1.ref), InboundConnection)
    }

    expectTerminated(probe0.ref)
    expectTerminated(probe1.ref)
  }

  it should "publish unreachable when connection broken" in new Fixture {
    val broker0  = relevantBrokerInfo().info
    val probe0   = TestProbe()
    val broker1  = relevantBrokerInfo().info
    val probe1   = TestProbe()
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[DiscoveryServer.Unreachable])

    def test(
        brokerHandler: TestProbe,
        brokerInfo: BrokerInfo,
        connectionType: ConnectionType
    ): Unit = {
      interCliqueManagerActor.context.watchWith(
        brokerHandler.ref,
        InterCliqueManager.PeerDisconnected(brokerInfo.address)
      )
      publishHandShaked(brokerHandler.ref, brokerInfo, connectionType)
    }

    test(probe0, broker0, InboundConnection)
    system.stop(probe0.ref)
    eventually(listener.expectMsg(DiscoveryServer.Unreachable(broker0.address)))
    eventually(interCliqueManagerActor.brokers.contains(broker0.peerId) is false)

    test(probe1, broker1, OutboundConnection)
    system.stop(probe1.ref)
    eventually(listener.expectMsg(DiscoveryServer.Unreachable(broker1.address)))
    eventually(interCliqueManagerActor.brokers.contains(broker1.peerId) is false)
  }

  behavior of "Extract peers"

  it should "not return self clique" in new Fixture {
    interCliqueManagerActor.extractPeersToConnect(cliqueInfo.interBrokers.get, 100).isEmpty is true
  }

  it should "not return already included peers" in new Fixture {
    val testBroker = relevantBrokerInfo()
    interCliqueManagerActor.addBroker(testBroker)
    interCliqueManagerActor.extractPeersToConnect(AVector(testBroker.info), 100).isEmpty is true
  }

  it should "not return non-intersected peers" in new Fixture {
    val testBroker = irrelevantBrokerInfo().info
    interCliqueManagerActor.extractPeersToConnect(AVector(testBroker), 100).isEmpty is true
  }

  it should "not return any peers when capacity is 0" in new Fixture {
    val testBroker = relevantBrokerInfo().info
    interCliqueManagerActor.extractPeersToConnect(AVector(testBroker), 0).isEmpty is true
  }

  it should "return the peer when capacity is ok" in new Fixture {
    val testBroker = relevantBrokerInfo().info
    interCliqueManagerActor.extractPeersToConnect(AVector(testBroker), 1) is AVector(testBroker)
  }

  it should "not return any peers when there are enough pending connections" in new Fixture {
    val broker0 = relevantBrokerInfo().info
    val broker1 = relevantBrokerInfo().info
    interCliqueManagerActor.connecting.put(broker0.address, broker0)
    interCliqueManagerActor.extractPeersToConnect(AVector(broker1), 1).isEmpty is true
  }

  behavior of "Sync"

  trait SyncFixture extends Fixture {
    def checkSynced(expected: Boolean) = {
      interCliqueManager ! InterCliqueManager.IsSynced
      expectMsg(InterCliqueManager.SyncedResult(expected))
    }

    def addAndCheckSynced(expected: Boolean) = {
      val broker = relevantBrokerInfo().info
      interCliqueManagerActor.brokers.contains(broker.peerId) is false
      publishHandShaked(testActor, broker, InboundConnection)
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
      blockFlowSynchronizer.expectMsg(SyncedResult(synced))
      allHandlerProbes.txHandler.expectMsg(SyncedResult(synced))
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

    interCliqueManagerActor.brokers.clear()
    interCliqueManagerActor.updateNodeSyncedStatus()
    eventually(discoveryServer.expectMsgType[DiscoveryServer.GetMorePeers])
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

    interCliqueManagerActor.brokers.isEmpty is true // unlazy the actor
    publishHandShaked(broker0.ref, brokerInfo0)
    publishHandShaked(broker1.ref, brokerInfo1)
    publishHandShaked(broker2.ref, brokerInfo2)
    publishHandShaked(broker3.ref, brokerInfo3)
    interCliqueManagerActor.brokers.contains(brokerInfo0.peerId) willBe true
    interCliqueManagerActor.brokers.contains(brokerInfo1.peerId) willBe true
    interCliqueManagerActor.brokers.contains(brokerInfo2.peerId) willBe true
    interCliqueManagerActor.brokers.contains(brokerInfo3.peerId) willBe false

    interCliqueManager ! CliqueManager.Synced(brokerInfo0)
    interCliqueManager ! CliqueManager.Synced(brokerInfo1)

    val message = genBroadCastBlock(ChainIndex.unsafe(0, 1), DataOrigin.Local)
    interCliqueManager ! message
    broker0.expectNoMessage()
    broker1.expectNoMessage()
    broker2.expectNoMessage()

    interCliqueManagerActor.lastNodeSyncedStatus = Some(true)
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

    interCliqueManagerActor.brokers.isEmpty is true // unlazy the actor
    publishHandShaked(broker0.ref, brokerInfo0)
    publishHandShaked(broker1.ref, brokerInfo1)
    publishHandShaked(broker2.ref, brokerInfo2)
    publishHandShaked(broker3.ref, brokerInfo3)
    interCliqueManagerActor.brokers.contains(brokerInfo0.peerId) willBe true
    interCliqueManagerActor.brokers.contains(brokerInfo1.peerId) willBe true
    interCliqueManagerActor.brokers.contains(brokerInfo2.peerId) willBe true
    interCliqueManagerActor.brokers.contains(brokerInfo3.peerId) willBe true

    interCliqueManager ! CliqueManager.Synced(brokerInfo0)
    interCliqueManager ! CliqueManager.Synced(brokerInfo1)
    interCliqueManager ! CliqueManager.Synced(brokerInfo2)

    val message = genBroadCastBlock(ChainIndex.unsafe(0, 1), DataOrigin.InterClique(brokerInfo0))
    interCliqueManager ! message
    broker0.expectNoMessage()
    broker1.expectNoMessage()
    broker2.expectNoMessage()
    broker3.expectNoMessage()

    interCliqueManagerActor.lastNodeSyncedStatus = Some(true)
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

    interCliqueManagerActor.brokers.isEmpty is true // unlazy the actor
    publishHandShaked(broker0.ref, brokerInfo0)
    publishHandShaked(broker1.ref, brokerInfo1)
    publishHandShaked(broker2.ref, brokerInfo2)
    publishHandShaked(broker3.ref, brokerInfo3)
    interCliqueManagerActor.brokers.contains(brokerInfo0.peerId) willBe true
    interCliqueManagerActor.brokers.contains(brokerInfo1.peerId) willBe true
    interCliqueManagerActor.brokers.contains(brokerInfo2.peerId) willBe true
    interCliqueManagerActor.brokers.contains(brokerInfo3.peerId) willBe true

    interCliqueManager ! CliqueManager.Synced(brokerInfo0)
    interCliqueManager ! CliqueManager.Synced(brokerInfo1)
    interCliqueManager ! CliqueManager.Synced(brokerInfo2)

    val txHashes   = AVector.fill(4)(TransactionId.generate)
    val chainIndex = ChainIndex.unsafe(0, 1)
    val message    = InterCliqueManager.BroadCastTx(AVector((chainIndex, txHashes)))
    interCliqueManager ! message
    broker0.expectMsg(BrokerHandler.RelayTxs(AVector((chainIndex, txHashes))))
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
    ): InterCliqueManager.BroadCastBlock = {
      val block = emptyBlock(blockFlow, chainIndex)
      InterCliqueManager.BroadCastBlock(
        block,
        Message.serialize(NewBlock(block)),
        origin
      )
    }
  }

  it should "get sync status" in {
    class TestNodeSyncStatus extends InterCliqueManager.NodeSyncStatus {
      def receive = updateNodeSyncStatus
    }

    val actor = TestActorRef[TestNodeSyncStatus](Props(new TestNodeSyncStatus))
    actor ! InterCliqueManager.IsSynced
    eventually(expectMsg(InterCliqueManager.SyncedResult(false)))
    actor ! InterCliqueManager.SyncedResult(true)
    actor ! InterCliqueManager.IsSynced
    eventually(expectMsg(InterCliqueManager.SyncedResult(true)))
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

    def irrelevantBrokerInfo(connectionType: ConnectionType = InboundConnection): BrokerState = {
      val broker = brokerInfoGen.retryUntil(!_.intersect(brokerConfig)).sample.get
      BrokerState(broker, connectionType, TestProbe().ref, false, clientInfo)
    }

    def relevantBrokerInfo(connectionType: ConnectionType = InboundConnection): BrokerState = {
      val broker = brokerInfoGen.retryUntil(_.intersect(brokerConfig)).sample.get
      BrokerState(broker, connectionType, TestProbe().ref, false, clientInfo)
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
