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
import akka.testkit.{TestProbe}
import akka.util.Timeout
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.core.{BlockFlow, EmptyBlockFlow}
import org.alephium.flow.handler.TestUtils
import org.alephium.flow.io.{Storages, StoragesFixture}
import org.alephium.flow.setting.{AlephiumConfig, AlephiumConfigFixture}
import org.alephium.protocol.Generators
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util.{ActorRefT, AlephiumActorSpec, BaseActor, Duration, Random}

class InterCliqueManagerSpec
    extends AlephiumActorSpec("InterCliqueManagerSpec")
    with Generators
    with ScalaFutures {

  implicit val timeout: Timeout = Timeout(Duration.ofSecondsUnsafe(2).asScala)

  it should "forward clique info to discovery server on start" in new Fixture {
    discoveryServer.expectMsg(DiscoveryServer.SendCliqueInfo(cliqueInfo))
  }

  it should "publish `PeerDisconected` on inbound peer disconnection" in new Fixture {

    interCliqueManager ! Tcp.Connected(peer, socketAddressGen.sample.get)

    eventually {
      val name    = BaseActor.envalidActorName(s"InboundBrokerHandler-$peer")
      val inbound = getActor(name)(system.dispatcher).futureValue

      inbound.isDefined is true

      system.stop(inbound.get)
    }

    disconnectionProbe.expectMsg(InterCliqueManager.PeerDisconected(peer))
  }

  it should "publish `PeerDisconected` on outbound peer disconnection" in new Fixture {

    interCliqueManager ! DiscoveryServer.NewPeer(peerInfo)

    eventually {
      val name     = BaseActor.envalidActorName(s"OutboundBrokerHandler-$peerInfo")
      val outbound = getActor(name)(system.dispatcher).futureValue

      outbound.isDefined is true

      system.stop(outbound.get)
    }

    disconnectionProbe.expectMsg(InterCliqueManager.PeerDisconected(peerInfo.address))
  }

  trait Fixture extends AlephiumConfigFixture with StoragesFixture.Default {

    val cliqueInfo = cliqueInfoGen.sample.get

    val discoveryServer       = TestProbe()
    val blockFlowSynchronizer = TestProbe()
    val (allHandlers, _)      = TestUtils.createBlockHandlersProbe
    val blockflow: BlockFlow  = new InterCliqueManagerSpec.EmptyBlockFlowImpl(storages)

    val parentName = s"InterCliqueManager-${Random.source.nextInt}"
    val interCliqueManager = system.actorOf(
      InterCliqueManager.props(cliqueInfo,
                               blockflow,
                               allHandlers,
                               ActorRefT(discoveryServer.ref),
                               ActorRefT(blockFlowSynchronizer.ref)),
      parentName)

    val disconnectionProbe = TestProbe()
    system.eventStream
      .subscribe(disconnectionProbe.ref, classOf[InterCliqueManager.PeerDisconected])

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
  }
}

object InterCliqueManagerSpec {
  class EmptyBlockFlowImpl(val storages: Storages)(implicit val config: AlephiumConfig)
      extends EmptyBlockFlow
}
