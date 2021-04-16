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

import akka.actor.{ActorRef, ActorSystem}
import akka.io.Tcp
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.FlowFixture
import org.alephium.flow.handler.TestUtils
import org.alephium.flow.network.broker.{InboundConnection, OutboundConnection}
import org.alephium.protocol.Generators
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util._

class InterCliqueManagerSpec
    extends AlephiumActorSpec("InterCliqueManagerSpec")
    with Generators
    with ScalaFutures {
  implicit override lazy val system: ActorSystem =
    ActorSystem(name, ConfigFactory.parseString(AlephiumActorSpec.debugConfig))

  implicit val timeout: Timeout = Timeout(Duration.ofSecondsUnsafe(2).asScala)

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

    discoveryServer.expectMsg(DiscoveryServer.PeerDisconnected(peer))

    getPeers() is Seq.empty
  }

  it should "publish `PeerDisconnected` on outbound peer disconnection" in new Fixture {
    interCliqueManager ! DiscoveryServer.NewPeer(peerInfo)

    eventually {
      val connection = getActor("*")(system.dispatcher).futureValue.get
      interCliqueManager.tell(CliqueManager.HandShaked(peerInfo, OutboundConnection), connection)
      getPeers() is Seq(peer)

      system.stop(connection)
    }

    discoveryServer.expectMsg(DiscoveryServer.SendCliqueInfo(cliqueInfo))
    discoveryServer.expectMsg(DiscoveryServer.PeerDisconnected(peerInfo.address))

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
      interCliqueManagerActor.handleNewBroker(newBroker, InboundConnection)
    }
  }

  it should "not accept outbound connection when the number of outbound connections is large" in new Fixture {
    override val configValues = Map(
      ("alephium.network.max-outbound-connections-per-group", 1)
    )

    val broker = relevantBrokerInfo()
    EventFilter.debug(start = "Too many outbound connections", occurrences = 0).intercept {
      interCliqueManagerActor.handleNewBroker(broker, OutboundConnection)
    }

    val newBroker = newBrokerInfo(broker)
    EventFilter.debug(start = "Too many outbound connections", occurrences = 1).intercept {
      interCliqueManagerActor.handleNewBroker(newBroker, OutboundConnection)
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

  trait Fixture extends FlowFixture with Generators {
    lazy val maxOutboundConnectionsPerGroup: Int = config.network.maxOutboundConnectionsPerGroup
    lazy val maxInboundConnectionsPerGroup: Int  = config.network.maxInboundConnectionsPerGroup

    lazy val cliqueInfo = cliqueInfoGen.sample.get

    lazy val discoveryServer       = TestProbe()
    lazy val blockFlowSynchronizer = TestProbe()
    lazy val (allHandlers, _)      = TestUtils.createBlockHandlersProbe

    lazy val parentName = s"InterCliqueManager-${Random.nextInt()}"
    lazy val interCliqueManager = TestActorRef[InterCliqueManager](
      InterCliqueManager.props(
        cliqueInfo,
        blockFlow,
        allHandlers,
        ActorRefT(discoveryServer.ref),
        ActorRefT(blockFlowSynchronizer.ref)
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
        info.groupNumPerBroker,
        socketAddressGen.sample.get
      )
    }
  }
}
