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

import scala.annotation.tailrec

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
import org.alephium.flow.network.broker.{ConnectionHandler, MisbehaviorManager}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.{BlockHash, Generators}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model.{ChainIndex, CliqueInfo}
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

  it should "mark block seen when receive inventories" in new Fixture {
    val chainIndex = ChainIndex.unsafe(brokerConfig.groupFrom, brokerConfig.groupFrom)
    def genValidBlockHash(): BlockHash = {
      emptyBlock(blockFlow, chainIndex).hash
    }

    val blockHash1 = genValidBlockHash()
    brokerHandler ! BaseBrokerHandler.Received(
      InvResponse(RequestId.random(), AVector(AVector(blockHash1)))
    )
    eventually(brokerHandler.underlyingActor.seenBlocks.contains(blockHash1) is true)

    val blockHash2 = genValidBlockHash()
    brokerHandler ! BaseBrokerHandler.Received(NewInv(AVector(AVector(blockHash2))))
    eventually(brokerHandler.underlyingActor.seenBlocks.contains(blockHash2) is true)

    val blockHash3 = genValidBlockHash()
    brokerHandler ! BaseBrokerHandler.Received(NewBlockHash(blockHash3))
    eventually(brokerHandler.underlyingActor.seenBlocks.contains(blockHash3) is true)

    val block1 = emptyBlock(blockFlow, chainIndex)
    brokerHandler ! BaseBrokerHandler.Received(BlocksResponse(RequestId.random(), AVector(block1)))
    eventually(brokerHandler.underlyingActor.seenBlocks.contains(block1.hash) is true)

    val block2 = emptyBlock(blockFlow, chainIndex)
    brokerHandler ! BaseBrokerHandler.Received(NewBlocks(AVector(block2)))
    eventually(brokerHandler.underlyingActor.seenBlocks.contains(block2.hash) is true)
  }

  it should "publish misbehavior when receive invalid block hash" in new Fixture {
    @tailrec
    def genInvalidBlockHash(): BlockHash = {
      val hash = BlockHash.generate
      if (brokerConfig.contains(ChainIndex.from(hash).from)) {
        genInvalidBlockHash()
      } else {
        hash
      }
    }

    val blockHash = genInvalidBlockHash()
    val listener  = TestProbe()

    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    brokerHandler ! BaseBrokerHandler.Received(NewBlockHash(blockHash))
    listener.expectMsg(
      MisbehaviorManager.InvalidFlowChainIndex(brokerHandler.underlyingActor.remoteAddress)
    )
    brokerHandler.underlyingActor.seenBlocks.contains(blockHash) is false
  }

  it should "send announcement only if remote have not seen the block" in new Fixture {
    val blockHash1 = BlockHash.generate
    val blockHash2 = BlockHash.generate

    brokerHandler.underlyingActor.seenBlocks.put(blockHash1, ())
    brokerHandler ! BaseBrokerHandler.RelayInventory(blockHash1)
    connectionHandler.expectNoMessage()

    brokerHandler ! BaseBrokerHandler.RelayInventory(blockHash2)
    val message = Message.serialize(NewBlockHash(blockHash2), networkSetting.networkType)
    connectionHandler.expectMsg(ConnectionHandler.Send(message))
    brokerHandler.underlyingActor.seenBlocks.contains(blockHash2) is true
  }

  trait Fixture {
    val (allHandler, _)   = TestUtils.createAllHandlersProbe
    val cliqueManager     = TestProbe()
    val connectionHandler = TestProbe()

    val brokerHandler = TestActorRef[TestBrokerHandler](
      TestBrokerHandler.props(
        Generators.cliqueInfoGen.sample.get,
        Generators.socketAddressGen.sample.get,
        ActorRefT(TestProbe().ref),
        blockFlow,
        allHandler,
        ActorRefT(cliqueManager.ref),
        ActorRefT(TestProbe().ref),
        ActorRefT(connectionHandler.ref)
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
      blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
      brokerConnectionHandler: ActorRefT[ConnectionHandler.Command]
  )(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): Props =
    Props(
      new TestBrokerHandler(
        selfCliqueInfo,
        remoteAddress,
        connection,
        blockflow,
        allHandlers,
        cliqueManager,
        blockFlowSynchronizer,
        brokerConnectionHandler
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
    val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
    override val brokerConnectionHandler: ActorRefT[ConnectionHandler.Command]
)(implicit val brokerConfig: BrokerConfig, val networkSetting: NetworkSetting)
    extends BaseInboundBrokerHandler
    with BrokerHandler {
  override def receive: Receive = exchanging
}
