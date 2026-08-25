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

import org.apache.pekko.actor.Props
import org.apache.pekko.io.Tcp
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.scalacheck.Gen

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, FlowHandler, TestUtils, TxHandler}
import org.alephium.flow.network.{CliqueManager, MaxTxsRequestNum}
import org.alephium.flow.network.broker.{InboundBrokerHandler => BaseInboundBrokerHandler}
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler}
import org.alephium.flow.network.broker.{ConnectionHandler, MisbehaviorManager}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.Generators
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model._
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
    brokerHandlerActor.handleHandshakeInfo(invalidBrokerInfo, clientInfo, P2PV2)
    expectTerminated(brokerHandler)
  }

  it should "compute the headers and blocks for sync" in new Fixture with ModelGenerators {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-id", 1))

    config.broker.brokerNum is 3
    config.broker.groupNumPerBroker is 1
    config.broker.brokerId is 1

    completeHandshake(remoteBrokerId = 0)

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

  it should "reject a short intra-clique inventory" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-id", 1))

    completeHandshake(remoteBrokerId = 0)
    val hashes = AVector.fill(groups0 - 1)(AVector.empty[BlockHash])
    expectInvalidInventory(hashes)
  }

  it should "reject an oversized intra-clique inventory" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-id", 1))

    completeHandshake(remoteBrokerId = 0)
    val hashes = AVector.fill(groups0 + 1)(AVector.empty[BlockHash])
    expectInvalidInventory(hashes)
  }

  it should "keep inventory extraction total for invalid broker metadata" in new Fixture {
    val invalidBrokerInfo = BrokerInfo.unsafe(
      cliqueInfo.id,
      brokerId = 0,
      brokerNum = 0,
      address = Generators.socketAddressGen.sample.get
    )
    BrokerHandler.extractToSync(blockFlow, AVector.empty, invalidBrokerInfo).isLeft is true
  }

  it should "send inventories to broker" in new Fixture {
    val inventories = AVector.fill(4)(BlockHash.generate)
    brokerHandler ! FlowHandler.SyncInventories(None, AVector(inventories))
    val message = Message.serialize(NewInv(AVector(inventories)))
    connectionHandler.expectMsg(ConnectionHandler.Send(message))
  }

  it should "handle TxsResponse" in new Fixture with ModelGenerators {
    def txGen(chainIndexGen: Gen[ChainIndex]) = {
      AVector
        .from(Gen.listOfN(4, transactionGen(chainIndexGen = chainIndexGen)).sample.get)
        .map(_.toTemplate)
    }

    brokerConfig.brokerId is 0
    brokerConfig.brokerNum is 3
    val validIndexesGen =
      chainIndexGen.retryUntil(index => index.from.value != 0 && index.to.value == 0)
    val inValidIndexesGen =
      chainIndexGen.retryUntil(index => index.from.value == 0 || index.to.value != 0)
    val validTxs        = txGen(validIndexesGen)
    val invalidTxs      = txGen(inValidIndexesGen)
    val validResponse   = TxsResponse(RequestId.unsafe(0), validTxs)
    val invalidResponse = TxsResponse(RequestId.unsafe(0), invalidTxs)

    brokerHandler ! BaseBrokerHandler.Received(validResponse)
    allHandlerProbes.txHandler.expectMsg(
      TxHandler.AddToMemPool(validTxs, isIntraCliqueSyncing = true, isLocalTx = false)
    )

    watch(brokerHandler)
    brokerHandler ! BaseBrokerHandler.Received(invalidResponse)
    expectTerminated(brokerHandler)
  }

  it should "reject an empty-input transaction response without throwing" in new Fixture
    with ModelGenerators {
    brokerConfig.brokerId is 0
    brokerConfig.brokerNum is 3
    val validIndexesGen =
      chainIndexGen.retryUntil(index => index.from.value != 0 && index.to.value == 0)
    val tx        = transactionGen(chainIndexGen = validIndexesGen).sample.get.toTemplate
    val invalidTx = tx.copy(unsigned = tx.unsigned.copy(inputs = AVector.empty))

    watch(brokerHandler)
    brokerHandler ! BaseBrokerHandler.Received(
      TxsResponse(RequestId.unsafe(0), AVector(invalidTx))
    )
    expectTerminated(brokerHandler)
  }

  it should "reject an unexpected transaction response id" in new Fixture with ModelGenerators {
    val validIndexesGen =
      chainIndexGen.retryUntil(index => index.from.value != 0 && index.to.value == 0)
    val tx       = transactionGen(chainIndexGen = validIndexesGen).sample.get.toTemplate
    val listener = TestProbe()

    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    watch(brokerHandler)
    brokerHandler ! BaseBrokerHandler.Received(TxsResponse(RequestId.unsafe(1), AVector(tx)))

    listener.expectMsg(MisbehaviorManager.InvalidResponse(brokerHandlerActor.remoteAddress))
    expectTerminated(brokerHandler)
    allHandlerProbes.txHandler.expectNoMessage()
  }

  it should "reject an oversized transaction response" in new Fixture with ModelGenerators {
    val validIndexesGen =
      chainIndexGen.retryUntil(index => index.from.value != 0 && index.to.value == 0)
    val tx       = transactionGen(chainIndexGen = validIndexesGen).sample.get.toTemplate
    val txs      = AVector.fill(MaxTxsRequestNum + 1)(tx)
    val listener = TestProbe()

    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    watch(brokerHandler)
    brokerHandler ! BaseBrokerHandler.Received(TxsResponse(RequestId.unsafe(0), txs))

    listener.expectMsg(MisbehaviorManager.Spamming(brokerHandlerActor.remoteAddress))
    expectTerminated(brokerHandler)
    allHandlerProbes.txHandler.expectNoMessage()
  }

  trait Fixture extends FlowFixture {
    val connectionHandler = TestProbe()
    lazy val cliqueInfo   = Generators.cliqueInfoGen.sample.get

    lazy val (allHandler, allHandlerProbes) = TestUtils.createAllHandlersProbe
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

    def completeHandshake(remoteBrokerId: Int): BrokerInfo = {
      val brokerInfo = BrokerInfo.unsafe(
        cliqueInfo.id,
        remoteBrokerId,
        config.broker.brokerNum,
        Generators.socketAddressGen.sample.get
      )
      brokerHandlerActor.handleHandshakeInfo(brokerInfo, clientInfo, P2PV2)
      brokerInfo
    }

    def expectInvalidInventory(hashes: AVector[AVector[BlockHash]]): Unit = {
      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.InvalidGroup])
      watch(brokerHandler)

      brokerHandler ! BaseBrokerHandler.Received(NewInv(hashes))

      listener.expectMsg(MisbehaviorManager.InvalidGroup(brokerHandlerActor.remoteAddress))
      expectTerminated(brokerHandler)
      connectionHandler.expectNoMessage()
    }

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

  override def receive: Receive = exchangingV2
}
