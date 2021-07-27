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

package org.alephium.flow.network.interclique

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.Tcp
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import org.scalatest.concurrent.Eventually.eventually

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, FlowHandler, TestUtils}
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler}
import org.alephium.flow.network.broker.{InboundBrokerHandler => BaseInboundBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.Generators
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{InvResponse, RequestId}
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.{ActorRefT, AVector}

class BrokerHandlerSpec extends AlephiumFlowActorSpec("BrokerHandlerSpec") {
  it should "set remote synced" in new Fixture {
    brokerHandler.underlyingActor.selfSynced is false
    brokerHandler.underlyingActor.remoteSynced is false

    EventFilter.info(start = "Remote ").intercept {
      brokerHandler ! FlowHandler.SyncInventories(None, AVector(AVector.empty))
    }
    brokerHandler.underlyingActor.selfSynced is false
    brokerHandler.underlyingActor.remoteSynced is true
    cliqueManager.expectNoMessage()

    EventFilter.info(start = "Remote ", occurrences = 0).intercept {
      brokerHandler ! FlowHandler.SyncInventories(None, AVector(AVector.empty))
    }
  }

  it should "set self synced" in new Fixture {
    brokerHandler.underlyingActor.selfSynced is false
    brokerHandler.underlyingActor.remoteSynced is false

    EventFilter.info(start = "Self synced").intercept {
      brokerHandler ! BaseBrokerHandler.Received(InvResponse(RequestId.random(), AVector.empty))
    }
    brokerHandler.underlyingActor.selfSynced is true
    brokerHandler.underlyingActor.remoteSynced is false
    cliqueManager.expectNoMessage()

    EventFilter.info(start = "Self synced", occurrences = 0).intercept {
      brokerHandler ! BaseBrokerHandler.Received(InvResponse(RequestId.random(), AVector.empty))
    }
  }

  it should "set synced" in new Fixture {
    brokerHandler.underlyingActor.selfSynced is false
    brokerHandler.underlyingActor.remoteSynced is false

    brokerHandler ! FlowHandler.SyncInventories(None, AVector(AVector.empty))
    brokerHandler ! BaseBrokerHandler.Received(InvResponse(RequestId.random(), AVector.empty))
    eventually {
      brokerHandler.underlyingActor.selfSynced is true
      brokerHandler.underlyingActor.remoteSynced is true
    }
    cliqueManager.expectMsg(CliqueManager.Synced(brokerHandler.underlyingActor.remoteBrokerInfo))
  }

  trait Fixture {
    val (allHandler, _) = TestUtils.createAllHandlersProbe
    val cliqueManager   = TestProbe()

    val brokerHandler = TestActorRef[TestBrokerHandler](
      TestBrokerHandler.props(
        Generators.cliqueInfoGen.sample.get,
        Generators.socketAddressGen.sample.get,
        ActorRefT(TestProbe().ref),
        blockFlow,
        allHandler,
        ActorRefT(cliqueManager.ref),
        ActorRefT(TestProbe().ref)
      )
    )
  }
}

object TestBrokerHandler {
  // scalastyle:off parameter.number
  def props(
      selfCliqueInfo: CliqueInfo,
      remoteAddress: InetSocketAddress,
      connection: ActorRefT[Tcp.Command],
      blockflow: BlockFlow,
      allHandlers: AllHandlers,
      cliqueManager: ActorRefT[CliqueManager.Command],
      blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command]
  )(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): Props =
    Props(
      new TestBrokerHandler(
        selfCliqueInfo,
        remoteAddress,
        connection,
        blockflow,
        allHandlers,
        cliqueManager,
        blockFlowSynchronizer
      )
    )
}

class TestBrokerHandler(
    val selfCliqueInfo: CliqueInfo,
    val remoteAddress: InetSocketAddress,
    val connection: ActorRefT[Tcp.Command],
    val blockflow: BlockFlow,
    val allHandlers: AllHandlers,
    val cliqueManager: ActorRefT[CliqueManager.Command],
    val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command]
)(implicit val brokerConfig: BrokerConfig, val networkSetting: NetworkSetting)
    extends BaseInboundBrokerHandler
    with BrokerHandler {
  override def receive: Receive = exchanging
}
