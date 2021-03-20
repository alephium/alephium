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

import akka.actor.ActorRef
import akka.io.Tcp
import akka.testkit.{TestActorRef, TestProbe}
import akka.util.Timeout
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

  implicit val timeout: Timeout = Timeout(Duration.ofSecondsUnsafe(2).asScala)

  it should "forward clique info to discovery server on start" in new Fixture {
    discoveryServer.expectMsg(DiscoveryServer.SendCliqueInfo(cliqueInfo))
  }

  it should "publish `PeerDisconnected` on inbound peer disconnection" in new Fixture {

    discoveryServer.expectMsg(DiscoveryServer.SendCliqueInfo(cliqueInfo))

    val connection = TestProbe()
    connection.send(interCliqueManager, Tcp.Connected(peer, socketAddressGen.sample.get))

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

    discoveryServer.expectMsg(DiscoveryServer.SendCliqueInfo(cliqueInfo))

    interCliqueManager ! DiscoveryServer.NewPeer(peerInfo)

    eventually {
      val connection = getActor("*")(system.dispatcher).futureValue.get
      interCliqueManager.tell(CliqueManager.HandShaked(peerInfo, OutboundConnection), connection)
      getPeers() is Seq(peer)

      system.stop(connection)
    }

    discoveryServer.expectMsg(DiscoveryServer.PeerDisconnected(peerInfo.address))

    getPeers() is Seq.empty
  }

  it should "check for unknown incoming connections" in new Fixture {
    interCliqueManagerActor.checkForInConnection(0) is false
    interCliqueManagerActor.checkForInConnection(1) is true
  }

  it should "not include brokers that are not related to our groups" in new Fixture {
    val badBroker = irrelevantBrokerInfo()
    interCliqueManagerActor.checkForOutConnection(badBroker, maxOutboundConnectionsPerGroup) is false
    interCliqueManagerActor.checkForInConnection(badBroker, maxInboundConnectionsPerGroup) is false
  }

  it should "not re-add existing brokers" in new Fixture {
    val broker = relevantBrokerInfo()
    interCliqueManagerActor.checkForOutConnection(broker, maxOutboundConnectionsPerGroup) is true
    interCliqueManagerActor.addBroker(broker, OutboundConnection, TestProbe().ref)
    interCliqueManagerActor.checkForOutConnection(broker, maxOutboundConnectionsPerGroup) is false
  }

  it should "not accept incoming connection when the number of inbound connections is large" in new Fixture {
    val broker = relevantBrokerInfo()
    interCliqueManagerActor.checkForInConnection(broker, maxInboundConnectionsPerGroup) is true
    interCliqueManagerActor.checkForInConnection(broker, 0) is false

    val newBroker = newBrokerInfo(broker)
    interCliqueManagerActor.addBroker(broker, InboundConnection, TestProbe().ref)
    interCliqueManagerActor.checkForInConnection(newBroker, 1) is false
    interCliqueManagerActor.checkForInConnection(newBroker, 2) is true
  }

  it should "not accept outbound connection when the number of outbound connections is large" in new Fixture {
    val broker = relevantBrokerInfo()
    interCliqueManagerActor.checkForOutConnection(broker, maxOutboundConnectionsPerGroup) is true
    interCliqueManagerActor.checkForOutConnection(broker, 0) is false

    val newBroker = newBrokerInfo(broker)
    interCliqueManagerActor.addBroker(broker, OutboundConnection, TestProbe().ref)
    interCliqueManagerActor.checkForOutConnection(newBroker, 1) is false
    interCliqueManagerActor.checkForOutConnection(newBroker, 2) is true
  }

  trait Fixture extends FlowFixture with Generators {

    val cliqueInfo = cliqueInfoGen.sample.get

    val discoveryServer       = TestProbe()
    val blockFlowSynchronizer = TestProbe()
    val (allHandlers, _)      = TestUtils.createBlockHandlersProbe

    val parentName = s"InterCliqueManager-${Random.source.nextInt}"
    val interCliqueManager = TestActorRef[InterCliqueManager](
      InterCliqueManager.props(cliqueInfo,
                               blockFlow,
                               allHandlers,
                               ActorRefT(discoveryServer.ref),
                               ActorRefT(blockFlowSynchronizer.ref)),
      parentName)
    val interCliqueManagerActor = interCliqueManager.underlyingActor

    lazy val peer = socketAddressGen.sample.get

    lazy val peerInfo = BrokerInfo.unsafe(cliqueIdGen.sample.get,
                                          brokerConfig.brokerId,
                                          cliqueInfo.groupNumPerBroker,
                                          peer)

    def getActor(name: String)(
        implicit executionContext: ExecutionContext): Future[Option[ActorRef]] =
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
      BrokerInfo.unsafe(cliqueIdGen.sample.get,
                        info.brokerId,
                        info.groupNumPerBroker,
                        socketAddressGen.sample.get)
    }
  }
}
