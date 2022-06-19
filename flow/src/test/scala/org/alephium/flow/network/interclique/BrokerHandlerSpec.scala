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
import org.scalacheck.Gen
import org.scalatest.concurrent.Eventually.eventually

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, DependencyHandler, FlowHandler, TestUtils, TxHandler}
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler}
import org.alephium.flow.network.broker.{InboundBrokerHandler => BaseInboundBrokerHandler}
import org.alephium.flow.network.broker.{ConnectionHandler, MisbehaviorManager}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.{BlockHash, Generators, Hash}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model.{ChainIndex, CliqueInfo, NoIndexModelGeneratorsLike}
import org.alephium.util.{ActorRefT, AVector, TimeStamp, UnsecureRandom}

class BrokerHandlerSpec extends AlephiumFlowActorSpec {
  it should "set remote synced" in new Fixture {
    brokerHandlerActor.selfSynced is false
    brokerHandlerActor.remoteSynced is false

    EventFilter.info(start = "Remote ").intercept {
      brokerHandler ! FlowHandler.SyncInventories(Some(RequestId.random()), AVector(AVector.empty))
    }
    brokerHandlerActor.selfSynced is false
    brokerHandlerActor.remoteSynced is true
    cliqueManager.expectNoMessage()

    EventFilter.info(start = "Remote ", occurrences = 0).intercept {
      brokerHandler ! FlowHandler.SyncInventories(Some(RequestId.random()), AVector(AVector.empty))
    }
  }

  it should "set self synced" in new Fixture {
    brokerHandlerActor.selfSynced is false
    brokerHandlerActor.remoteSynced is false

    EventFilter.info(start = "Self synced").intercept {
      brokerHandler ! BaseBrokerHandler.Received(InvResponse(RequestId.random(), AVector.empty))
    }
    brokerHandlerActor.selfSynced is true
    brokerHandlerActor.remoteSynced is false
    cliqueManager.expectNoMessage()

    EventFilter.info(start = "Self synced", occurrences = 0).intercept {
      brokerHandler ! BaseBrokerHandler.Received(InvResponse(RequestId.random(), AVector.empty))
    }
  }

  it should "set synced" in new Fixture {
    brokerHandlerActor.selfSynced is false
    brokerHandlerActor.remoteSynced is false

    brokerHandler ! FlowHandler.SyncInventories(Some(RequestId.random()), AVector(AVector.empty))
    brokerHandler ! BaseBrokerHandler.Received(InvResponse(RequestId.random(), AVector.empty))
    eventually {
      brokerHandlerActor.selfSynced is true
      brokerHandlerActor.remoteSynced is true
    }
    cliqueManager.expectMsg(CliqueManager.Synced(brokerHandlerActor.remoteBrokerInfo))
  }

  it should "mark block seen when receive valid NewBlock/NewBlockHash" in new Fixture {
    val blockHash = emptyBlock(blockFlow, chainIndex).hash
    brokerHandler ! BaseBrokerHandler.Received(NewBlockHash(blockHash))
    eventually(brokerHandlerActor.seenBlocks.contains(blockHash) is true)

    val block = emptyBlock(blockFlow, chainIndex)
    brokerHandler ! BaseBrokerHandler.Received(NewBlock(block))
    eventually(brokerHandlerActor.seenBlocks.contains(block.hash) is true)
  }

  it should "ignore the duplicated block hash" in new Fixture {
    val block = emptyBlock(blockFlow, chainIndex)

    brokerHandler ! BaseBrokerHandler.Received(NewBlockHash(block.hash))
    blockFlowSynchronizer.expectMsg(
      BlockFlowSynchronizer.HandShaked(brokerHandlerActor.remoteBrokerInfo)
    )
    eventually(brokerHandlerActor.seenBlocks.contains(block.hash) is true)
    blockFlowSynchronizer.expectMsg(BlockFlowSynchronizer.BlockAnnouncement(block.hash))
    brokerHandler ! BaseBrokerHandler.Received(NewBlockHash(block.hash))
    blockFlowSynchronizer.expectNoMessage()
  }

  it should "not mark block seen when receive BlocksResponse/HeadersResponse/InvResponse" in new Fixture {
    val block = emptyBlock(blockFlow, chainIndex)
    brokerHandler ! BaseBrokerHandler.Received(BlocksResponse(RequestId.random(), AVector(block)))
    eventually(brokerHandlerActor.seenBlocks.contains(block.hash)) is false

    val blockHeader = emptyBlock(blockFlow, chainIndex).header
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersResponse(RequestId.random(), AVector(blockHeader))
    )
    eventually(brokerHandlerActor.seenBlocks.contains(blockHeader.hash)) is false

    val blockHash = emptyBlock(blockFlow, chainIndex).hash
    brokerHandler ! BaseBrokerHandler.Received(
      InvResponse(RequestId.random(), AVector(AVector(blockHash)))
    )
    eventually(brokerHandlerActor.seenBlocks.contains(blockHash)) is false
  }

  it should "query header verified blocks" in new Fixture {
    val requestId = RequestId.random()
    val block     = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    def requestBlocks() = {
      brokerHandler ! BaseBrokerHandler.Received(BlocksRequest(requestId, AVector(block.hash)))
    }

    EventFilter.error(start = "IO error in load block").intercept(requestBlocks())
    blockFlow.cacheHeaderVerifiedBlock(block)
    requestBlocks()
    connectionHandler.expectMsg {
      val payload = BlocksResponse(requestId, AVector(block))
      ConnectionHandler.Send(Message.serialize(payload))
    }
  }

  it should "publish misbehavior when receive invalid hash/block/header" in new Fixture
    with NoIndexModelGeneratorsLike {
    val invalidHash   = genInvalidBlockHash()
    val listener      = TestProbe()
    val remoteAddress = brokerHandlerActor.remoteAddress

    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    brokerHandler ! BaseBrokerHandler.Received(NewBlockHash(invalidHash))
    listener.expectMsg(MisbehaviorManager.InvalidFlowChainIndex(remoteAddress))
    brokerHandlerActor.seenBlocks.contains(invalidHash) is false

    val invalidBlock =
      blockGen(ChainIndex.unsafe(nonBrokerGroup, nonBrokerGroup)).sample.get
    brokerHandler ! BaseBrokerHandler.Received(NewBlock(invalidBlock))
    listener.expectMsg(MisbehaviorManager.InvalidFlowChainIndex(remoteAddress))
    brokerHandlerActor.seenBlocks.contains(invalidBlock.hash) is false

    val invalidHeader = emptyBlock(
      blockFlow,
      ChainIndex.unsafe(brokerGroup, brokerGroup)
    ).header
    brokerHandler ! BaseBrokerHandler.Received(NewHeader(invalidHeader))
    listener.expectMsg(MisbehaviorManager.InvalidFlowChainIndex(remoteAddress))
    brokerHandlerActor.seenBlocks.contains(invalidHeader.hash) is false
  }

  it should "publish misbehavior when receive invalid pow block hash" in new Fixture {
    override val configValues = Map(("alephium.consensus.num-zeros-at-least-in-hash", 1))

    val invalidPoWBlock = invalidNonceBlock(blockFlow, chainIndex)
    val listener        = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    watch(brokerHandler)
    val remoteAddress = brokerHandlerActor.remoteAddress
    brokerHandler ! BaseBrokerHandler.Received(NewBlockHash(invalidPoWBlock.hash))
    listener.expectMsg(MisbehaviorManager.InvalidPoW(remoteAddress))
    expectTerminated(brokerHandler.ref)
  }

  it should "send announcements only if remote have not seen the block" in new Fixture {
    val blockHash1 = BlockHash.generate
    val blockHash2 = BlockHash.generate

    brokerHandlerActor.seenBlocks.put(blockHash1, ())
    brokerHandler ! BaseBrokerHandler.RelayBlock(blockHash1)
    connectionHandler.expectNoMessage()

    brokerHandler ! BaseBrokerHandler.RelayBlock(blockHash2)
    val message = Message.serialize(NewBlockHash(blockHash2))
    connectionHandler.expectMsg(ConnectionHandler.Send(message))
    brokerHandlerActor.seenBlocks.contains(blockHash2) is true
  }

  it should "publish misbehavior when receive deep forked block" in new Fixture {
    val invalidForkedBlock = emptyBlock(blockFlow, chainIndex)
    val listener           = TestProbe()
    val blockChain         = blockFlow.getBlockChain(chainIndex)

    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    blockChain.maxHeightUnsafe is 2
    val validForkedBlock = emptyBlock(blockFlow, chainIndex)
    (0 until maxForkDepth).foreach(_ => addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex)))
    blockChain.maxHeightUnsafe is (2 + maxForkDepth)

    brokerHandler ! BaseBrokerHandler.Received(NewBlock(validForkedBlock))
    val message = DependencyHandler.AddFlowData(AVector(validForkedBlock), dataOrigin)
    allHandlerProbes.dependencyHandler.expectMsg(message)

    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    val remoteAddress = brokerHandlerActor.remoteAddress
    watch(brokerHandler)
    brokerHandler ! BaseBrokerHandler.Received(NewBlock(invalidForkedBlock))
    listener.expectMsg(MisbehaviorManager.DeepForkBlock(remoteAddress))
  }

  it should "cleanup cache based on capacity" in new Fixture {
    val capacity = brokerConfig.groupNumPerBroker * brokerConfig.groups * 10
    brokerHandlerActor.maxBlockCapacity is capacity
    brokerHandlerActor.maxTxsCapacity is (capacity * 32)
    setSynced()
    val txHash0 = Hash.generate
    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((chainIndex, AVector(txHash0)))))
    brokerHandlerActor.seenTxs.contains(txHash0) is true
    val txHashes = AVector.fill(brokerHandlerActor.maxTxsCapacity)(Hash.generate)
    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((chainIndex, txHashes))))
    brokerHandlerActor.seenTxs.contains(txHash0) is false
    brokerHandlerActor.seenTxs.keys().toSet is txHashes.toSet
  }

  it should "mark tx seen when receive valid tx announcements" in new Fixture {
    val txHashes = AVector.fill(10)(Hash.generate)
    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((chainIndex, txHashes))))
    brokerHandlerActor.seenTxs.isEmpty is true
    setSynced()
    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((chainIndex, txHashes))))
    brokerHandlerActor.seenTxs.keys().toSet is txHashes.toSet
    allHandlerProbes.txHandler.expectMsg(
      TxHandler.TxAnnouncements(AVector((chainIndex, txHashes)))
    )
  }

  it should "not mark tx seen when receive TxsResponse" in new Fixture
    with NoIndexModelGeneratorsLike {
    val chainIndexGen = Gen.const(chainIndex)
    val txs = AVector.fill(10)(transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate)
    brokerHandler ! BaseBrokerHandler.Received(TxsResponse(RequestId.random(), txs))
    allHandlerProbes.txHandler.expectMsg(TxHandler.AddToSharedPool(txs))
    txs.foreach { tx =>
      brokerHandlerActor.seenTxs.contains(tx.id) is false
    }
  }

  it should "publish misbehavior when receive invalid tx announcements" in new Fixture {
    val listener = TestProbe()
    val txHashes = AVector.fill(10)(Hash.generate)
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    watch(brokerHandler)
    setSynced()
    val remoteAddress = brokerHandlerActor.remoteAddress
    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((invalidChainIndex, txHashes))))
    listener.expectMsg(MisbehaviorManager.InvalidGroup(remoteAddress))
  }

  it should "ignore the duplicated tx announcements" in new Fixture {
    val txHashes1 = AVector.fill(6)(Hash.generate)
    val txHashes2 = AVector.fill(4)(Hash.generate)

    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((chainIndex, txHashes1))))
    brokerHandlerActor.seenTxs.isEmpty is true
    allHandlerProbes.txHandler.expectNoMessage()
    setSynced()
    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((chainIndex, txHashes1))))
    allHandlerProbes.txHandler.expectMsg(
      TxHandler.TxAnnouncements(AVector((chainIndex, txHashes1)))
    )
    brokerHandler ! BaseBrokerHandler.Received(
      NewTxHashes(AVector((chainIndex, txHashes1.take(2) ++ txHashes2)))
    )
    allHandlerProbes.txHandler.expectMsg(
      TxHandler.TxAnnouncements(AVector((chainIndex, txHashes2)))
    )
    brokerHandlerActor.seenTxs.size is (txHashes1.length + txHashes2.length)
    brokerHandlerActor.seenTxs.keys().toSet is (txHashes1 ++ txHashes2).toSet
  }

  it should "send announcements only if remote have not seen the tx" in new Fixture {
    val txHash1 = Hash.generate
    val txHash2 = Hash.generate

    brokerHandlerActor.seenTxs.put(txHash1, ())
    brokerHandler ! BaseBrokerHandler.RelayTxs(AVector((chainIndex, AVector(txHash1))))
    connectionHandler.expectNoMessage()
    brokerHandlerActor.seenTxs.keys().toSet is Set(txHash1)

    brokerHandler ! BaseBrokerHandler.RelayTxs(AVector((chainIndex, AVector(txHash1, txHash2))))
    val message = Message.serialize(NewTxHashes(AVector((chainIndex, AVector(txHash2)))))
    connectionHandler.expectMsg(ConnectionHandler.Send(message))
    brokerHandlerActor.seenTxs.keys().toSet is Set(txHash1, txHash2)
  }

  it should "handle TxsRequest" in new Fixture with NoIndexModelGeneratorsLike {
    val chainIndexGen = Gen.const(chainIndex)
    val txs = AVector.fill(4)(transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate)
    txs.foreach { tx =>
      val mempool = blockFlow.getMemPool(chainIndex)
      mempool.addNewTx(chainIndex, tx, TimeStamp.now())
      mempool.getSharedPool(chainIndex).contains(tx.id) is true
    }

    val txHashes = txs.take(3).map(_.id) :+ Hash.generate
    val request  = TxsRequest(RequestId.random(), AVector((chainIndex, txHashes)))
    brokerHandler ! BaseBrokerHandler.Received(request)
    val message = Message.serialize(TxsResponse(request.id, txs.take(3)))
    connectionHandler.expectMsg(ConnectionHandler.Send(message))

    val invalidRequest = TxsRequest(RequestId.random(), AVector((invalidChainIndex, txHashes)))
    val listener       = TestProbe()
    val remoteAddress  = brokerHandlerActor.remoteAddress
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    watch(brokerHandler)
    brokerHandler ! BaseBrokerHandler.Received(invalidRequest)
    listener.expectMsg(MisbehaviorManager.InvalidGroup(remoteAddress))
  }

  it should "handle TxsResponse" in new Fixture with NoIndexModelGeneratorsLike {
    val chainIndexGen = Gen.const(chainIndex)
    val txs = AVector.fill(4)(transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate)
    val response = TxsResponse(RequestId.random(), txs)
    brokerHandler ! BaseBrokerHandler.Received(response)
    allHandlerProbes.txHandler.expectMsg(TxHandler.AddToSharedPool(txs))

    val invalidTx =
      transactionGen(chainIndexGen = Gen.const(invalidChainIndex)).sample.get.toTemplate
    val invalidResponse = TxsResponse(RequestId.random(), txs :+ invalidTx)
    val listener        = TestProbe()
    val remoteAddress   = brokerHandlerActor.remoteAddress
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    watch(brokerHandler)
    brokerHandler ! BaseBrokerHandler.Received(invalidResponse)
    listener.expectMsg(MisbehaviorManager.InvalidGroup(remoteAddress))
  }

  it should "request inventories" in new Fixture {
    val locators = AVector.fill(4)(BlockHash.generate)
    brokerHandler ! BaseBrokerHandler.SyncLocators(AVector(locators))
    connectionHandler.expectMsgPF() { case ConnectionHandler.Send(message) =>
      Message
        .deserialize(message)
        .rightValue
        .payload
        .asInstanceOf[InvRequest]
        .locators is AVector(locators)
    }
  }

  it should "request txs" in new Fixture {
    val txHashes = AVector.fill(4)((chainIndex, AVector(Hash.generate)))
    brokerHandler ! BaseBrokerHandler.DownloadTxs(txHashes)
    connectionHandler.expectMsgPF() { case ConnectionHandler.Send(message) =>
      Message
        .deserialize(message)
        .rightValue
        .payload
        .asInstanceOf[TxsRequest]
        .hashes is txHashes
    }
  }

  it should "handle inventories request properly" in new Fixture {
    val hash0    = emptyBlock(blockFlow, chainIndex).hash
    val request0 = InvRequest(AVector(AVector(hash0)))
    brokerHandler ! BaseBrokerHandler.Received(request0)
    allHandlerProbes.flowHandler.expectMsg(
      FlowHandler.GetSyncInventories(
        request0.id,
        AVector(AVector(hash0)),
        brokerHandlerActor.remoteBrokerInfo
      )
    )

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    val hash1    = genInvalidBlockHash()
    val request1 = InvRequest(AVector(AVector(hash1)))
    brokerHandler ! BaseBrokerHandler.Received(request1)
    allHandlerProbes.flowHandler.expectNoMessage()
    listener.expectMsg(MisbehaviorManager.InvalidFlowChainIndex(brokerHandlerActor.remoteAddress))
  }

  trait Fixture extends FlowFixture {
    val cliqueManager         = TestProbe()
    val connectionHandler     = TestProbe()
    val blockFlowSynchronizer = TestProbe()
    val maxForkDepth          = 5

    lazy val (allHandler, allHandlerProbes) = TestUtils.createAllHandlersProbe
    lazy val brokerHandler = TestActorRef[TestBrokerHandler](
      TestBrokerHandler.props(
        Generators.cliqueInfoGen.sample.get,
        Generators.socketAddressGen.sample.get,
        ActorRefT(TestProbe().ref),
        blockFlow,
        allHandler,
        ActorRefT(cliqueManager.ref),
        ActorRefT(blockFlowSynchronizer.ref),
        ActorRefT(connectionHandler.ref),
        maxForkDepth
      )
    )
    lazy val brokerHandlerActor = brokerHandler.underlyingActor
    lazy val dataOrigin         = brokerHandlerActor.dataOrigin
    lazy val brokerGroup        = UnsecureRandom.sample(brokerConfig.groupRange)
    lazy val chainIndex         = ChainIndex.unsafe(brokerGroup, brokerGroup)
    lazy val nonBrokerGroup     = (brokerGroup + 1) % brokerConfig.groups
    lazy val invalidChainIndex  = ChainIndex.unsafe(nonBrokerGroup, nonBrokerGroup)

    def setSynced(): Unit = {
      brokerHandlerActor.selfSynced = true
      brokerHandlerActor.remoteSynced = true
    }

    @tailrec
    final def genInvalidBlockHash(): BlockHash = {
      val hash = BlockHash.generate
      if (brokerConfig.contains(ChainIndex.from(hash).from)) {
        genInvalidBlockHash()
      } else {
        hash
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
      blockflow: BlockFlow,
      allHandlers: AllHandlers,
      cliqueManager: ActorRefT[CliqueManager.Command],
      blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
      brokerConnectionHandler: ActorRefT[ConnectionHandler.Command],
      maxForkDepth: Int
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
        brokerConnectionHandler,
        maxForkDepth
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
    override val brokerConnectionHandler: ActorRefT[ConnectionHandler.Command],
    override val maxForkDepth: Int
)(implicit val brokerConfig: BrokerConfig, val networkSetting: NetworkSetting)
    extends BaseInboundBrokerHandler
    with BrokerHandler {
  context.watch(brokerConnectionHandler.ref)

  override def receive: Receive = exchanging
}
