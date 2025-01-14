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
import scala.collection.mutable
import scala.util.Random

import akka.actor.Props
import akka.io.Tcp
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import org.scalacheck.Gen

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, FlowHandler, TestUtils, TxHandler}
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler}
import org.alephium.flow.network.broker.{InboundBrokerHandler => BaseInboundBrokerHandler}
import org.alephium.flow.network.broker.{ChainTipInfo, ConnectionHandler, MisbehaviorManager}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.network.sync.SyncState.BlockDownloadTask
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.Generators
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model._
import org.alephium.serde.serialize
import org.alephium.util.{ActorRefT, AVector, Duration, TimeStamp, UnsecureRandom}

// scalastyle:off file.size.limit
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
    cliqueManager.expectMsg(CliqueManager.Synced(brokerHandlerActor.remoteBrokerInfo))

    EventFilter.info(start = "Self synced", occurrences = 0).intercept {
      brokerHandler ! BaseBrokerHandler.Received(InvResponse(RequestId.random(), AVector.empty))
    }
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
    eventually(brokerHandlerActor.seenBlocks.contains(block.hash) is true)
    blockFlowSynchronizer.expectMsg(BlockFlowSynchronizer.BlockAnnouncement(block.hash))
    brokerHandler ! BaseBrokerHandler.Received(NewBlockHash(block.hash))
    blockFlowSynchronizer.expectNoMessage()
  }

  it should "not mark block seen when receive BlocksResponse/HeadersResponse/InvResponse" in new Fixture {
    val block = emptyBlock(blockFlow, chainIndex)
    brokerHandler ! BaseBrokerHandler.Received(
      BlocksResponse.fromBlockBytes(RequestId.random(), AVector(serialize(block)))
    )
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
      val payload = BlocksResponse.fromBlockBytes(requestId, AVector(serialize(block)))
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
    override val configValues: Map[String, Any] = Map(
      ("alephium.consensus.num-zeros-at-least-in-hash", 1)
    )

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

  it should "cleanup cache based on capacity" in new Fixture {
    val capacity = brokerConfig.groupNumPerBroker * brokerConfig.groups * 10
    brokerHandlerActor.maxBlockCapacity is capacity
    brokerHandlerActor.maxTxsCapacity is (capacity * 32)
    setSynced()
    val txHash0 = TransactionId.generate
    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((chainIndex, AVector(txHash0)))))
    brokerHandlerActor.seenTxs.contains(txHash0) is true
    val txHashes = AVector.fill(brokerHandlerActor.maxTxsCapacity)(TransactionId.generate)
    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((chainIndex, txHashes))))
    brokerHandlerActor.seenTxs.contains(txHash0) is false
    brokerHandlerActor.seenTxs.keys().toSet is txHashes.toSet
  }

  it should "mark tx seen when receive valid tx announcements" in new Fixture {
    val txHashes = AVector.fill(10)(TransactionId.generate)
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
    allHandlerProbes.txHandler.expectMsg(
      TxHandler.AddToMemPool(txs, isIntraCliqueSyncing = false, isLocalTx = false)
    )
    txs.foreach { tx =>
      brokerHandlerActor.seenTxs.contains(tx.id) is false
    }
  }

  it should "publish misbehavior when receive invalid tx announcements" in new Fixture {
    val listener = TestProbe()
    val txHashes = AVector.fill(10)(TransactionId.generate)
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    watch(brokerHandler)
    setSynced()
    val remoteAddress = brokerHandlerActor.remoteAddress
    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((invalidChainIndex, txHashes))))
    listener.expectMsg(MisbehaviorManager.InvalidGroup(remoteAddress))
  }

  it should "ignore the duplicated tx announcements" in new Fixture {
    val txHashes1 = AVector.fill(6)(TransactionId.generate)
    val txHashes2 = AVector.fill(4)(TransactionId.generate)

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
    val txHash1 = TransactionId.generate
    val txHash2 = TransactionId.generate

    brokerHandlerActor.seenTxs.put(txHash1, TimeStamp.now())
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
      blockFlow.getGrandPool().add(chainIndex, tx, TimeStamp.now())
      mempool.contains(tx.id) is true
    }

    val txHashes = txs.take(3).map(_.id) :+ TransactionId.generate
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
    allHandlerProbes.txHandler.expectMsg(
      TxHandler.AddToMemPool(txs, isIntraCliqueSyncing = false, isLocalTx = false)
    )

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
    val txHashes = AVector.fill(4)((chainIndex, AVector(TransactionId.generate)))
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

  it should "remove seen txs based on expiry duration" in new Fixture {
    setSynced()

    val Seq(txHash0, txHash1, txHash2, txHash3) = Seq.fill(4)(TransactionId.generate)
    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((chainIndex, AVector(txHash0)))))
    brokerHandler ! BaseBrokerHandler.RelayTxs(AVector((chainIndex, AVector(txHash2))))
    eventually(brokerHandler.underlyingActor.seenTxs.contains(txHash0) is true)
    eventually(brokerHandler.underlyingActor.seenTxs.contains(txHash1) is false)
    eventually(brokerHandler.underlyingActor.seenTxs.contains(txHash2) is true)
    eventually(brokerHandler.underlyingActor.seenTxs.contains(txHash3) is false)

    Thread.sleep((seenTxExpiryDuration + Duration.ofSecondsUnsafe(1)).millis)

    brokerHandler ! BaseBrokerHandler.Received(NewTxHashes(AVector((chainIndex, AVector(txHash1)))))
    brokerHandler ! BaseBrokerHandler.RelayTxs(AVector((chainIndex, AVector(txHash3))))
    eventually(brokerHandler.underlyingActor.seenTxs.contains(txHash0) is false)
    eventually(brokerHandler.underlyingActor.seenTxs.contains(txHash1) is true)
    eventually(brokerHandler.underlyingActor.seenTxs.contains(txHash2) is false)
    eventually(brokerHandler.underlyingActor.seenTxs.contains(txHash3) is true)
  }

  it should "send chain state to peer" in new Fixture {
    val tips = genChainTips()
    brokerHandler ! BaseBrokerHandler.SendChainState(tips)
    connectionHandler.expectMsgPF() { case ConnectionHandler.Send(message) =>
      Message
        .deserialize(message)
        .rightValue
        .payload
        .asInstanceOf[ChainState]
        .tips is tips
    }
  }

  it should "receive valid chain state from peer" in new Fixture {
    setRemoteBrokerInfo()
    val tips = genChainTips()
    brokerHandler ! BaseBrokerHandler.Received(ChainState(tips))
    blockFlowSynchronizer.expectMsg(BlockFlowSynchronizer.UpdateChainState(tips))
  }

  it should "stop handler and publish misbehavior if the tip size is invalid" in new Fixture {
    val invalidTips = genChainTips().drop(1)
    checkInvalidTips(invalidTips)
  }

  it should "stop handler and publish misbehavior if the tip hash is invalid" in new Fixture {
    override val configValues: Map[String, Any] =
      Map(("alephium.consensus.num-zeros-at-least-in-hash", 1))

    val invalidBlock = invalidNonceBlock(blockFlow, chainIndex)
    val invalidTip   = ChainTip(invalidBlock.hash, 1, invalidBlock.weight)
    val invalidTips  = genChainTips().replace(0, invalidTip)
    checkInvalidTips(invalidTips)
  }

  it should "stop handler and publish misbehavior if the tip chain index is invalid" in new Fixture {
    val tips        = genChainTips()
    val invalidTips = tips.replace(1, tips(0))
    checkInvalidTips(invalidTips)
  }

  it should "calculate request span" in {
    // the test vectors are from go-ethereum
    val testCases = Seq(
      (1500, 1000, AVector(1323, 1339, 1355, 1371, 1387, 1403, 1419, 1435, 1451, 1467, 1483, 1499)),
      (
        15000,
        13006,
        AVector(14823, 14839, 14855, 14871, 14887, 14903, 14919, 14935, 14951, 14967, 14983, 14999)
      ),
      (1200, 1150, AVector(1149, 1154, 1159, 1164, 1169, 1174, 1179, 1184, 1189, 1194, 1199)),
      (1500, 1500, AVector(1497, 1499)),
      (1000, 1500, AVector(997, 999)),
      (0, 1500, AVector(0, 2)),
      (
        6000000,
        0,
        AVector(5999823, 5999839, 5999855, 5999871, 5999887, 5999903, 5999919, 5999935, 5999951,
          5999967, 5999983, 5999999)
      ),
      (0, 0, AVector(0, 2))
    )
    testCases.foreach { case (remoteHeight, localHeight, requestHeights) =>
      SyncV2Handler.calculateRequestSpan(remoteHeight, localHeight).heights is requestHeights
    }
  }

  trait SyncV2Fixture extends Fixture {
    val defaultRequestId = RequestId.unsafe(1)

    def expectHeadersRequest(chains: AVector[(ChainIndex, BlockHeightRange)]): RequestId = {
      var requestId: RequestId = RequestId.unsafe(0)
      connectionHandler.expectMsgPF() { case ConnectionHandler.Send(message) =>
        val payload = Message
          .deserialize(message)
          .rightValue
          .payload
          .asInstanceOf[HeadersByHeightsRequest]
        brokerHandlerActor.pendingRequests.contains(payload.id) is true
        requestId = payload.id
        payload.data is AVector.from(chains)
      }
      requestId
    }

    def genBlocks(size: Int): AVector[Block] = {
      val blocks = (0 until size).map { _ =>
        val block = emptyBlock(blockFlow, chainIndex)
        addAndCheck(blockFlow, block)
        block
      }
      blockFlow.getMaxHeightByWeight(chainIndex) isE size
      AVector.from(blocks)
    }
  }

  trait GetAncestorsFixture extends SyncV2Fixture {
    import SyncV2Handler._

    def prepare(chainIndex: ChainIndex, bestHeight: Int): Unit = {
      val bestTip = ChainTip(BlockHash.random, bestHeight, Weight.zero)
      val state   = StatePerChain(chainIndex, bestTip)
      brokerHandlerActor.findingAncestorStates = Some(AVector(state))
      val request = HeadersByHeightsRequest(
        defaultRequestId,
        AVector((chainIndex, BlockHeightRange.fromHeight(0)))
      )
      brokerHandlerActor.pendingRequests(request.id) = RequestInfo(request, None)
    }

    def checkInvalidHeaders(headerss: AVector[AVector[BlockHeader]]) = {
      setRemoteBrokerInfo()

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
      watch(brokerHandler)

      prepare(chainIndex, 1)
      brokerHandler ! BaseBrokerHandler.Received(
        HeadersByHeightsResponse(defaultRequestId, headerss)
      )
      blockFlowSynchronizer.expectNoMessage()
      listener.expectMsg(MisbehaviorManager.InvalidFlowData(brokerHandlerActor.remoteAddress))
      expectTerminated(brokerHandler.ref)
    }
  }

  it should "handle GetAncestors request" in new GetAncestorsFixture {
    val chains = brokerConfig.chainIndexes.map { chainIndex =>
      ChainTipInfo(chainIndex, genChainTip(chainIndex), genChainTip(chainIndex))
    }
    brokerHandler ! BaseBrokerHandler.GetAncestors(chains)

    val requests = mutable.ArrayBuffer.empty[(ChainIndex, BlockHeightRange)]
    brokerHandlerActor.findingAncestorStates.isDefined is true
    brokerHandlerActor.findingAncestorStates.get.foreachWithIndex { case (state, index) =>
      val ChainTipInfo(chainIndex, bestTip, selfTip) = chains(index)
      state.chainIndex is chainIndex
      state.bestTip is bestTip
      state.binarySearch is None
      state.ancestor is None
      requests.addOne(
        (chainIndex, SyncV2Handler.calculateRequestSpan(bestTip.height, selfTip.height))
      )
    }
    expectHeadersRequest(AVector.from(requests))
  }

  it should "clear the pending requests when the GetAncestors request is received" in new GetAncestorsFixture {
    brokerHandler ! BaseBrokerHandler.GetSkeletons(
      AVector((chainIndex, BlockHeightRange.from(50, 100, 50)))
    )
    eventually(brokerHandlerActor.pendingRequests.nonEmpty is true)
    val requestId = brokerHandlerActor.pendingRequests.head._1

    val chains = brokerConfig.chainIndexes.map { chainIndex =>
      ChainTipInfo(chainIndex, genChainTip(chainIndex), genChainTip(chainIndex))
    }
    brokerHandler ! BaseBrokerHandler.GetAncestors(chains)
    eventually(brokerHandlerActor.pendingRequests.contains(requestId) is false)
  }

  it should "handle GetAncestors response" in new GetAncestorsFixture {
    prepare(chainIndex, 1)
    val blocks = genBlocks(4)
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(defaultRequestId, AVector(blocks.map(_.header)))
    )
    blockFlowSynchronizer.expectMsg(BlockFlowSynchronizer.UpdateAncestors(AVector((chainIndex, 4))))
    brokerHandlerActor.pendingRequests.isEmpty is true
    brokerHandlerActor.findingAncestorStates.isEmpty is true
  }

  it should "publish misbehavior and stop broker if the ancestors response is invalid" in {
    info("invalid chain size")
    new GetAncestorsFixture {
      checkInvalidHeaders(AVector.empty)
    }

    info("invalid header size")
    new GetAncestorsFixture {
      checkInvalidHeaders(AVector(AVector.empty))
    }

    info("invalid header hash")
    new GetAncestorsFixture {
      override val configValues: Map[String, Any] =
        Map(("alephium.consensus.num-zeros-at-least-in-hash", 1))

      val invalidBlock = invalidNonceBlock(blockFlow, chainIndex)
      checkInvalidHeaders(AVector(AVector(invalidBlock.header)))
    }

    info("invalid chain index")
    new GetAncestorsFixture {
      override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

      val block0 = emptyBlock(blockFlow, chainIndex)
      val block1 = emptyBlock(
        blockFlow,
        ChainIndex.unsafe(chainIndex.from.value, (chainIndex.to.value + 1) % brokerConfig.groups)
      )
      checkInvalidHeaders(AVector(AVector(block0.header, block1.header)))
    }
  }

  it should "fall back to binary search to find the ancestor" in new GetAncestorsFixture {
    val headers       = genBlocks(12).map(_.header)
    val unknownHeader = emptyBlock(blockFlow, chainIndex).header
    prepare(chainIndex, 30)

    val state = brokerHandlerActor.findingAncestorStates.get.head
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(defaultRequestId, AVector(AVector(unknownHeader)))
    )
    state.binarySearch is Some((0, 30))
    state.ancestor is None

    val requestId0 = expectHeadersRequest(AVector((chainIndex, BlockHeightRange.fromHeight(15))))
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(requestId0, AVector(AVector(unknownHeader)))
    )
    state.binarySearch is Some((0, 15))
    state.ancestor is None

    val requestId1 = expectHeadersRequest(AVector((chainIndex, BlockHeightRange.fromHeight(7))))
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(requestId1, AVector(AVector(headers(6))))
    )
    state.binarySearch is Some((7, 15))
    state.ancestor is Some(headers(6))

    val requestId2 = expectHeadersRequest(AVector((chainIndex, BlockHeightRange.fromHeight(11))))
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(requestId2, AVector(AVector(headers(10))))
    )
    state.binarySearch is Some((11, 15))
    state.ancestor is Some(headers(10))

    val requestId3 = expectHeadersRequest(AVector((chainIndex, BlockHeightRange.fromHeight(13))))
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(requestId3, AVector(AVector(unknownHeader)))
    )
    state.binarySearch is Some((11, 13))
    state.ancestor is Some(headers(10))

    val requestId4 = expectHeadersRequest(AVector((chainIndex, BlockHeightRange.fromHeight(12))))
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(requestId4, AVector(AVector(headers(11))))
    )
    blockFlowSynchronizer.expectMsg(
      BlockFlowSynchronizer.UpdateAncestors(AVector((chainIndex, 12)))
    )
    state.binarySearch.isEmpty is true
    connectionHandler.expectNoMessage()
    brokerHandlerActor.findingAncestorStates.isEmpty is true
  }

  it should "work when receiving genesis header" in new GetAncestorsFixture {
    import SyncV2Handler.RequestInfo
    prepare(chainIndex, 2)
    val request = HeadersByHeightsRequest(
      defaultRequestId,
      AVector((chainIndex, BlockHeightRange.from(0, 2, 2)))
    )
    brokerHandlerActor.pendingRequests(defaultRequestId) = RequestInfo(request, None)

    val header        = emptyBlock(blockFlow, chainIndex).header
    val blockchain    = blockFlow.getBlockChain(chainIndex)
    val genesisHeader = blockFlow.getBlockHeaderUnsafe(blockchain.genesisHash)
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(defaultRequestId, AVector(AVector(genesisHeader, header)))
    )
    blockFlowSynchronizer.expectMsg(BlockFlowSynchronizer.UpdateAncestors(AVector((chainIndex, 0))))
    connectionHandler.expectNoMessage()
    brokerHandlerActor.findingAncestorStates.isEmpty is true
  }

  it should "work if the self chain height is genesis height" in new GetAncestorsFixture {
    val unknownHeader = emptyBlock(blockFlow, chainIndex).header
    prepare(chainIndex, 30)

    val state         = brokerHandlerActor.findingAncestorStates.get.head
    var lastRequestId = defaultRequestId
    var lastHeight    = 30
    (0 until 4).foreach { _ =>
      brokerHandler ! BaseBrokerHandler.Received(
        HeadersByHeightsResponse(lastRequestId, AVector(AVector(unknownHeader)))
      )
      state.binarySearch is Some((0, lastHeight))
      lastHeight = lastHeight / 2
      lastRequestId =
        expectHeadersRequest(AVector((chainIndex, BlockHeightRange.fromHeight(lastHeight))))
      blockFlowSynchronizer.expectNoMessage()
      brokerHandlerActor.findingAncestorStates.isDefined is true
    }

    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(lastRequestId, AVector(AVector(unknownHeader)))
    )
    blockFlowSynchronizer.expectMsg(BlockFlowSynchronizer.UpdateAncestors(AVector((chainIndex, 0))))
    connectionHandler.expectNoMessage()
    brokerHandlerActor.findingAncestorStates.isEmpty is true
  }

  it should "handle HeadersByHeightsRequest" in new Fixture {
    val request = HeadersByHeightsRequest(
      RequestId.unsafe(1),
      AVector((chainIndex, BlockHeightRange.fromHeight(0)))
    )
    val blockchain = blockFlow.getBlockChain(chainIndex)
    val headers    = AVector(AVector(blockchain.getBlockHeaderUnsafe(blockchain.genesisHash)))
    brokerHandler ! BaseBrokerHandler.Received(request)
    connectionHandler.expectMsgPF() { case ConnectionHandler.Send(message) =>
      val payload = Message
        .deserialize(message)
        .rightValue
        .payload
        .asInstanceOf[HeadersByHeightsResponse]
      payload.id is RequestId.unsafe(1)
      payload.headers is headers
    }
  }

  trait GetSkeletonFixture extends SyncV2Fixture {
    import SyncV2Handler._

    def prepare(chains: AVector[(ChainIndex, BlockHeightRange)]) = {
      val request     = HeadersByHeightsRequest(defaultRequestId, chains)
      val requestInfo = RequestInfo(request, None)
      brokerHandlerActor.pendingRequests.addOne((request.id, requestInfo))
    }
  }

  it should "handle GetSkeleton request" in new GetSkeletonFixture {
    val chains = AVector((chainIndex, BlockHeightRange.from(50, 100, 50)))
    brokerHandler ! BaseBrokerHandler.GetSkeletons(chains)
    expectHeadersRequest(AVector.from(chains))
  }

  it should "handle GetSkeleton response" in new GetSkeletonFixture {
    val chains = AVector((chainIndex, BlockHeightRange.from(50, 100, 50)))
    prepare(chains)

    val headers = AVector.fill(2)(emptyBlock(blockFlow, chainIndex).header)
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(defaultRequestId, AVector(headers))
    )
    eventually(brokerHandlerActor.pendingRequests.contains(defaultRequestId) is false)
    blockFlowSynchronizer.expectMsg(BlockFlowSynchronizer.UpdateSkeletons(chains, AVector(headers)))

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    watch(brokerHandler)

    val invalidHeaders = headers.drop(1)
    prepare(chains)
    brokerHandler ! BaseBrokerHandler.Received(
      HeadersByHeightsResponse(defaultRequestId, AVector(invalidHeaders))
    )
    blockFlowSynchronizer.expectNoMessage()
    listener.expectMsg(MisbehaviorManager.InvalidFlowData(brokerHandlerActor.remoteAddress))
    expectTerminated(brokerHandler.ref)
  }

  trait DownloadBlocksFixture extends SyncV2Fixture {
    import SyncV2Handler._

    def prepare(tasks: AVector[BlockDownloadTask]) = {
      val chains      = tasks.map(t => (t.chainIndex, t.heightRange))
      val request     = BlocksByHeightsRequest(defaultRequestId, chains)
      val requestInfo = RequestInfo(request, Some(BaseBrokerHandler.DownloadBlockTasks(tasks)))
      brokerHandlerActor.pendingRequests.addOne((request.id, requestInfo))
    }
  }

  it should "handle download block tasks" in new DownloadBlocksFixture {
    val task = BlockDownloadTask(chainIndex, 1, 50, None)
    brokerHandler ! BaseBrokerHandler.DownloadBlockTasks(AVector(task))
    connectionHandler.expectMsgPF() { case ConnectionHandler.Send(message) =>
      val payload = Message
        .deserialize(message)
        .rightValue
        .payload
        .asInstanceOf[BlocksByHeightsRequest]
      payload.data is AVector((chainIndex, BlockHeightRange.from(1, 50, 1)))
    }
  }

  it should "handle blocks request" in new DownloadBlocksFixture {
    val blocks  = genBlocks(4)
    val heights = AVector((chainIndex, BlockHeightRange.from(1, 4, 1)))
    brokerHandler ! BaseBrokerHandler.Received(BlocksByHeightsRequest(defaultRequestId, heights))
    connectionHandler.expectMsgPF() { case ConnectionHandler.Send(message) =>
      val payload = Message
        .deserialize(message)
        .rightValue
        .payload
        .asInstanceOf[BlocksByHeightsResponse]
      payload.id is defaultRequestId
      payload.blocks is AVector(blocks)
    }
  }

  it should "handle blocks response" in new DownloadBlocksFixture {
    val blocks = genBlocks(4)
    val task0  = BlockDownloadTask(chainIndex, 1, 4, Some(blocks.last.header))
    prepare(AVector(task0))

    brokerHandler ! BaseBrokerHandler.Received(
      BlocksByHeightsResponse(defaultRequestId, AVector(blocks))
    )
    eventually(brokerHandlerActor.pendingRequests.contains(defaultRequestId) is false)
    blockFlowSynchronizer.expectMsg(
      BlockFlowSynchronizer.UpdateBlockDownloaded(AVector((task0, blocks, true)))
    )

    val task1 = task0.copy(toHeader = None)
    prepare(AVector(task1))
    brokerHandler ! BaseBrokerHandler.Received(
      BlocksByHeightsResponse(defaultRequestId, AVector(blocks))
    )
    blockFlowSynchronizer.expectMsg(
      BlockFlowSynchronizer.UpdateBlockDownloaded(AVector((task1, blocks, true)))
    )

    val task2 = task0.copy(toHeader = Some(blocks.head.header))
    prepare(AVector(task2))
    brokerHandler ! BaseBrokerHandler.Received(
      BlocksByHeightsResponse(defaultRequestId, AVector(blocks))
    )
    blockFlowSynchronizer.expectMsg(
      BlockFlowSynchronizer.UpdateBlockDownloaded(AVector((task2, blocks, false)))
    )
  }

  it should "validate blocks response" in new DownloadBlocksFixture {
    var blockSize = 0
    (0 until 4).foreach { _ =>
      val uncleSize = Random.nextInt(3)
      val blocks    = (0 to uncleSize).map(_ => emptyBlock(blockFlow, chainIndex))
      blocks.foreach(block => addAndCheck(blockFlow, block))
      blockSize += uncleSize + 1
    }

    val blockchain = blockFlow.getBlockChain(chainIndex)
    val blocks     = blockchain.getBlocksByHeightsUnsafe(AVector.from(1 to 4))
    blocks.length is blockSize

    def getHeader(height: Int): BlockHeader = {
      val hash = blockchain.getHashesUnsafe(height).head
      blockchain.getBlockHeaderUnsafe(hash)
    }
    val toHeader        = getHeader(4)
    val invalidToHeader = getHeader(3)

    SyncV2Handler.validateBlocks(blocks, 4, None) is true
    SyncV2Handler.validateBlocks(blocks, 4, Some(toHeader)) is true
    SyncV2Handler.validateBlocks(blocks, 3, None) is false
    SyncV2Handler.validateBlocks(blocks, 5, None) is false
    SyncV2Handler.validateBlocks(blocks, 4, Some(invalidToHeader)) is false

    val index         = blocks.indexWhere(_.header == invalidToHeader)
    val invalidBlocks = blocks.replace(index, blocks.last)
    SyncV2Handler.validateBlocks(invalidBlocks, 4, None) is false
    SyncV2Handler.validateBlocks(invalidBlocks, 4, Some(toHeader)) is false
  }

  it should "check pending requests" in new SyncV2Fixture {
    import SyncV2Handler.RequestInfo

    val now = TimeStamp.now()
    val request = HeadersByHeightsRequest(
      defaultRequestId,
      AVector((chainIndex, BlockHeightRange.fromHeight(1)))
    )
    val requestInfo = RequestInfo(request, None, now.minusUnsafe(Duration.ofSecondsUnsafe(3)))
    brokerHandlerActor.pendingRequests.addOne((defaultRequestId, requestInfo))

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    watch(brokerHandler)

    brokerHandler ! BaseBrokerHandler.CheckPendingRequest
    brokerHandlerActor.pendingRequests.size is 1

    brokerHandlerActor.pendingRequests.update(
      defaultRequestId,
      requestInfo.copy(expiry = now.plusSecondsUnsafe(2))
    )
    brokerHandler ! BaseBrokerHandler.CheckPendingRequest
    listener.expectMsg(MisbehaviorManager.RequestTimeout(brokerHandlerActor.remoteAddress))
    expectTerminated(brokerHandler.ref)
  }

  it should "check if the node is synced by chain state" in new SyncV2Fixture {
    setRemoteBrokerInfo()
    brokerHandlerActor.selfSynced is false
    brokerHandlerActor.remoteSynced is false

    val selfChainTips = genChainTips()
    brokerHandler ! BaseBrokerHandler.SendChainState(selfChainTips)
    brokerHandlerActor.selfSynced is false
    brokerHandlerActor.remoteSynced is false

    val remoteChainTips =
      selfChainTips.replace(0, selfChainTips(0).copy(weight = selfChainTips(0).weight + Weight(1)))
    brokerHandler ! BaseBrokerHandler.Received(ChainState(remoteChainTips))
    brokerHandlerActor.selfSynced is false
    brokerHandlerActor.remoteSynced is true

    brokerHandler ! BaseBrokerHandler.SendChainState(remoteChainTips)
    brokerHandlerActor.selfSynced is true
    brokerHandlerActor.remoteSynced is true
  }

  it should "get next height" in new Fixture {
    val bestTip = genChainTip(chainIndex).copy(height = 21)
    val state   = SyncV2Handler.StatePerChain(chainIndex, bestTip)
    state.ancestor.isEmpty is true
    state.binarySearch.isEmpty is true

    state.startBinarySearch()
    state.binarySearch is Some((0, 21))
    state.ancestor.isEmpty is true

    val header0 = emptyBlock(blockFlow, chainIndex).header
    state.getNextHeight(Some(header0)) is Some(15)
    state.binarySearch is Some((10, 21))
    state.ancestor is Some(header0)

    state.getNextHeight(None) is Some(12)
    state.binarySearch is Some((10, 15))
    state.ancestor is Some(header0)

    state.getNextHeight(None) is Some(11)
    state.binarySearch is Some((10, 12))
    state.ancestor is Some(header0)

    val header1 = emptyBlock(blockFlow, chainIndex).header
    state.getNextHeight(Some(header1)) is None
    state.binarySearch is Some((11, 12))
    state.ancestor is Some(header1)
    state.getNextHeight(None) is None
  }

  trait Fixture extends FlowFixture {
    val cliqueManager         = TestProbe()
    val connectionHandler     = TestProbe()
    val blockFlowSynchronizer = TestProbe()
    val seenTxExpiryDuration  = Duration.ofSecondsUnsafe(3)

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
        seenTxExpiryDuration
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

    def setRemoteBrokerInfo() = {
      brokerHandler.underlyingActor.remoteBrokerInfo = BrokerInfo.unsafe(
        CliqueId.generate,
        brokerConfig.brokerId,
        brokerConfig.brokerNum,
        brokerHandlerActor.remoteAddress
      )
    }

    def genChainTip(chainIndex: ChainIndex) = {
      val block = emptyBlock(blockFlow, chainIndex)
      ChainTip(block.hash, 1, block.weight)
    }

    def genChainTips() = brokerConfig.chainIndexes.map(genChainTip)

    def checkInvalidTips(invalidTips: AVector[ChainTip]) = {
      setRemoteBrokerInfo()

      val listener = TestProbe()
      system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
      watch(brokerHandler)

      brokerHandler ! BaseBrokerHandler.Received(ChainState(invalidTips))
      blockFlowSynchronizer.expectNoMessage()
      listener.expectMsg(MisbehaviorManager.InvalidChainState(brokerHandlerActor.remoteAddress))
      expectTerminated(brokerHandler.ref)
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
      seenTxExpiryDuration: Duration
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
        seenTxExpiryDuration
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
    override val seenTxExpiryDuration: Duration
)(implicit val brokerConfig: BrokerConfig, val networkSetting: NetworkSetting)
    extends BaseInboundBrokerHandler
    with BrokerHandler {
  context.watch(brokerConnectionHandler.ref)

  override def receive: Receive = exchangingV2
}
