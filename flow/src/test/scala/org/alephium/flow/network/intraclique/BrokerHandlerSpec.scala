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

package org.alephium.flow.network.intraclique

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.Tcp
import akka.testkit.{TestActorRef, TestProbe}

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, FlowHandler, TestUtils}
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{InboundBrokerHandler => BaseInboundBrokerHandler}
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler}
import org.alephium.flow.network.broker.ConnectionHandler
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.Generators
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{BlocksRequest, HeadersRequest, Message, NewInv, Payload}
import org.alephium.protocol.model.{BlockHash, BrokerInfo, CliqueInfo, GroupIndex, ModelGenerators}
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector}

class BrokerHandlerSpec extends AlephiumActorSpec {
  val clientInfo: String = "v0.0.0"

  it should "terminated when received invalid broker info" in new Fixture {
    config.broker.brokerNum is 3
    config.broker.groupNumPerBroker is 1
    config.broker.brokerId is 0

    val invalidBrokerInfo = BrokerInfo.unsafe(
      Generators.cliqueIdGen.sample.get,
      1,
      config.broker.brokerNum,
      Generators.socketAddressGen.sample.get
    )
    watch(brokerHandler)
    brokerHandlerActor.handleHandshakeInfo(invalidBrokerInfo, clientInfo)
    expectTerminated(brokerHandler)
  }

  it should "compute the headers and blocks for sync" in new Fixture with ModelGenerators {
    override val configValues = Map(("alephium.broker.broker-id", 1))

    config.broker.brokerNum is 3
    config.broker.groupNumPerBroker is 1
    config.broker.brokerId is 1

    val brokerInfo = BrokerInfo.unsafe(
      cliqueInfo.id,
      0,
      config.broker.brokerNum,
      Generators.socketAddressGen.sample.get
    )
    brokerHandlerActor.handleHandshakeInfo(brokerInfo, clientInfo)

    val blocks0 = AVector.tabulate(groups0) { _ =>
      blockGenOf(GroupIndex.unsafe(0)).sample.get
    }
    val hashes0 = blocks0.map(_.hash).map(AVector(_))
    brokerHandler ! BaseBrokerHandler.Received(NewInv(hashes0))
    expect[HeadersRequest].locators is (hashes0(0) ++ hashes0(2))
    expect[BlocksRequest].locators is hashes0(1)

    val blocks2 = AVector.tabulate(groups0) { _ =>
      blockGenOf(GroupIndex.unsafe(2)).sample.get
    }
    val hashes2 = blocks2.map(_.hash).map(AVector(_))
    brokerHandler ! BaseBrokerHandler.Received(NewInv(hashes2))
    expect[HeadersRequest].locators is (hashes2(0) ++ hashes2(2))
    expect[BlocksRequest].locators is (hashes2(1))
  }

  it should "send inventories to broker" in new Fixture {
    val inventories = AVector.fill(4)(BlockHash.generate)
    brokerHandler ! FlowHandler.SyncInventories(None, AVector(inventories))
    val message = Message.serialize(NewInv(AVector(inventories)))
    connectionHandler.expectMsg(ConnectionHandler.Send(message))
  }

  trait Fixture extends FlowFixture {
    val connectionHandler = TestProbe()
    lazy val cliqueInfo   = Generators.cliqueInfoGen.sample.get

    lazy val (allHandler, _) = TestUtils.createAllHandlersProbe
    lazy val brokerHandler = TestActorRef[TestBrokerHandler](
      TestBrokerHandler.props(
        cliqueInfo,
        Generators.socketAddressGen.sample.get,
        ActorRefT(TestProbe().ref),
        blockFlow,
        allHandler,
        ActorRefT(TestProbe().ref),
        ActorRefT(TestProbe().ref),
        ActorRefT(connectionHandler.ref)
      )
    )
    lazy val brokerHandlerActor = brokerHandler.underlyingActor

    def expect[T <: Payload]: T = {
      connectionHandler.expectMsgPF() { case ConnectionHandler.Send(data) =>
        Message.deserialize(data).rightValue.payload.asInstanceOf[T]
      }
    }
  }
}

object TestBrokerHandler {
  // scalastyle:off parameter.number
  def props(
      selfCliqueInfo: CliqueInfo,
      remoteAddress: InetSocketAddress,
      connection: ActorRefT[Tcp.Command],
      blockFlow: BlockFlow,
      allHandlers: AllHandlers,
      cliqueManager: ActorRefT[CliqueManager.Command],
      blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
      brokerConnectionHandler: ActorRefT[ConnectionHandler.Command]
  )(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): Props = {
    Props(
      new TestBrokerHandler(
        selfCliqueInfo,
        remoteAddress,
        connection,
        blockFlow,
        allHandlers,
        cliqueManager,
        blockFlowSynchronizer,
        brokerConnectionHandler
      )
    )
  }
}

class TestBrokerHandler(
    val selfCliqueInfo: CliqueInfo,
    val remoteAddress: InetSocketAddress,
    val connection: ActorRefT[Tcp.Command],
    val blockflow: BlockFlow,
    val allHandlers: AllHandlers,
    val cliqueManager: ActorRefT[CliqueManager.Command],
    val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
    override val brokerConnectionHandler: ActorRefT[ConnectionHandler.Command]
)(implicit val brokerConfig: BrokerConfig, val networkSetting: NetworkSetting)
    extends BaseInboundBrokerHandler
    with BrokerHandler {
  context.watch(brokerConnectionHandler.ref)

  override def receive: Receive = exchanging
}
