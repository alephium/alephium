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

package org.alephium.flow.handler

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import akka.testkit.{TestActorRef, TestProbe}

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.{maxSyncBlocksPerChain}
import org.alephium.flow.handler.TestUtils
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector, Duration, TimeStamp}

class DependencyHandlerSpec extends AlephiumActorSpec {
  trait Fixture extends FlowFixture { Self =>
    lazy val brokerProbe = TestProbe()
    lazy val broker      = ActorRefT[ChainHandler.Event](brokerProbe.ref)
    lazy val origin      = DataOrigin.Local

    lazy val (allHandlers, allHandlerProbes) = TestUtils.createAllHandlersProbe
    lazy val dependencyHandler = TestActorRef[DependencyHandler](
      DependencyHandler.props(blockFlow, allHandlers.blockHandlers, allHandlers.headerHandlers)
    )
    lazy val state = dependencyHandler.underlyingActor

    implicit class StatusWrapper(status: DependencyHandler.PendingStatus) {
      def extract(): (FlowData, ActorRefT[ChainHandler.Event], DataOrigin) = {
        (status.data, status.event, status.origin)
      }
    }
  }

  it should "work for valid data" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    val blockFlow1 = isolatedBlockFlow()

    val block0 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))
    state.addPendingData(block0, broker, origin, ArrayBuffer.empty)
    state.pending.unsafe(block0.hash).extract() is ((block0, broker, origin))
    state.missing.isEmpty is true
    state.missingIndex.isEmpty is true
    state.readies is mutable.HashSet(block0.hash)
    state.processing.isEmpty is true

    addAndCheck(blockFlow1, block0)
    val block1 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))
    val block2 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(1, 1))
    state.addPendingData(block1, broker, origin, ArrayBuffer.empty)
    state.addPendingData(block2, broker, origin, ArrayBuffer.empty)
    state.pending.unsafe(block1.hash).extract() is ((block1, broker, origin))
    state.pending.unsafe(block2.hash).extract() is ((block2, broker, origin))
    state.missing.keys.toSet is Set(block1.hash, block2.hash)
    state.missing(block1.hash) is ArrayBuffer(block0.hash)
    state.missing(block2.hash) is ArrayBuffer(block0.hash)
    state.missingIndex(block0.hash) is ArrayBuffer(block1.hash, block2.hash)
    state.readies is mutable.HashSet(block0.hash)
    state.processing.isEmpty is true

    addAndCheck(blockFlow1, block1)
    addAndCheck(blockFlow1, block2)
    val block3 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))
    state.addPendingData(block3, broker, origin, ArrayBuffer.empty)
    state.pending.unsafe(block3.hash).extract() is ((block3, broker, origin))
    state.missing.keys.toSet is Set(block1.hash, block2.hash, block3.hash)
    state.missing(block3.hash).toSet is Set(block1.hash, block2.hash)
    state.missingIndex(block0.hash) is ArrayBuffer(block1.hash, block2.hash)
    state.missingIndex(block1.hash) is ArrayBuffer(block3.hash)
    state.readies is mutable.HashSet(block0.hash)
    state.processing.isEmpty is true

    state.extractReadies().map(_.extract()).toSeq is Seq((block0, broker, origin))
    state.readies.isEmpty is true
    state.processing is mutable.HashSet(block0.hash)

    state.uponDataProcessed(block0)
    state.pending.contains(block0.hash) is false
    state.pending.contains(block1.hash) is true
    state.pending.contains(block2.hash) is true
    state.pending.contains(block3.hash) is true
    state.missing.keys.toSet is Set(block3.hash)
    state.missing(block3.hash).toSet is Set(block1.hash, block2.hash)
    state.missingIndex.keys.toSet is Set(block1.hash, block2.hash)
    state.missingIndex(block1.hash) is ArrayBuffer(block3.hash)
    state.missingIndex(block2.hash) is ArrayBuffer(block3.hash)
    state.readies is mutable.HashSet(block1.hash, block2.hash)
    state.processing.isEmpty is true

    state.extractReadies().map(_.extract()).toSet is
      Set[(FlowData, ActorRefT[ChainHandler.Event], DataOrigin)](
        (block1, broker, origin),
        (block2, broker, origin)
      )
    state.readies.isEmpty is true
    state.processing is mutable.HashSet(block1.hash, block2.hash)

    state.uponDataProcessed(block1)
    state.pending.contains(block0.hash) is false
    state.pending.contains(block1.hash) is false
    state.pending.contains(block2.hash) is true
    state.pending.contains(block3.hash) is true
    state.missing.keys.toSet is Set(block3.hash)
    state.missing(block3.hash).toSet is Set(block2.hash)
    state.missingIndex.keys.toSet is Set(block2.hash)
    state.missingIndex(block2.hash) is ArrayBuffer(block3.hash)
    state.readies.isEmpty is true
    state.processing is mutable.HashSet(block2.hash)

    state.uponDataProcessed(block2)
    state.pending.contains(block0.hash) is false
    state.pending.contains(block1.hash) is false
    state.pending.contains(block2.hash) is false
    state.pending.contains(block3.hash) is true
    state.missing.isEmpty is true
    state.missingIndex.isEmpty is true
    state.readies is mutable.HashSet(block3.hash)
    state.processing.isEmpty is true

    state.extractReadies().map(_.extract()).toSeq is Seq((block3, broker, origin))
    state.readies.isEmpty is true
    state.processing is mutable.HashSet(block3.hash)

    state.uponDataProcessed(block3)
    state.pending.isEmpty is true
    state.missingIndex.isEmpty is true
    state.readies.isEmpty is true
    state.processing.isEmpty is true
  }

  it should "work for invalid data" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    val n          = 3
    val blockFlow1 = isolatedBlockFlow()
    val blocks = (0 to n).map { _ =>
      brokerConfig.chainIndexes.map(mineFromMemPool(blockFlow1, _)).map { block =>
        addAndCheck(blockFlow1, block)
        state.addPendingData(block, broker, origin, ArrayBuffer.empty)
        block
      }
    }

    state.readies is mutable.HashSet(blocks.head.map(_.hash).toSeq: _*)
    state.pending.size is ((n + 1) * brokerConfig.chainNum)
    state.missing.size is (n * brokerConfig.chainNum)
    state.missingIndex.size is (n * brokerConfig.chainNum)

    blocks.head.foreach(block => state.uponInvalidData(block.hash))
    state.pending.isEmpty is true
    state.missing.isEmpty is true
    state.missingIndex.isEmpty is true
    state.readies.isEmpty is true
    state.processing.isEmpty is true
  }

  it should "work for unordered datas" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    val blockFlow1 = isolatedBlockFlow()
    val block0     = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow1, block0)
    val block1 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))

    state.addPendingData(block1, broker, origin, ArrayBuffer.empty)
    state.addPendingData(block0, broker, origin, ArrayBuffer.empty)
    state.pending.unsafe(block0.hash).extract() is ((block0, broker, origin))
    state.pending.unsafe(block1.hash).extract() is ((block1, broker, origin))
    state.missing.keys.toSet is Set(block1.hash)
    state.missing(block1.hash) is ArrayBuffer(block0.hash)
    state.missingIndex.keys.toSet is Set(block0.hash)
    state.missingIndex(block0.hash) is ArrayBuffer(block1.hash)
    state.readies is mutable.HashSet(block0.hash)
    state.processing.isEmpty is true
  }

  it should "not pend existing blocks" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    val block = mineFromMemPool(blockFlow, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow, block)
    state.addPendingData(block, broker, origin, ArrayBuffer.empty)
    state.pending.isEmpty is true
    state.missing.isEmpty is true
    state.missingIndex.isEmpty is true
    state.readies.isEmpty is true
    state.processing.isEmpty is true
  }

  it should "not pend in-processing blocks" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    val block = mineFromMemPool(blockFlow, ChainIndex.unsafe(0, 0))
    state.addPendingData(block, broker, origin, ArrayBuffer.empty)
    state.extractReadies()
    state.readies.isEmpty is true
    state.processing.isEmpty is false

    (0 until 10).foreach(_ => state.addPendingData(block, broker, origin, ArrayBuffer.empty))
    state.readies.isEmpty is true
    state.processing.isEmpty is false
  }

  it should "remove pending hashes based on capacity" in new Fixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.broker-num", 1),
      ("alephium.broker.groups", 1)
    )

    val cacheSize = maxSyncBlocksPerChain * 2
    state.cacheSize is cacheSize
    val block0 = mineFromMemPool(blockFlow, ChainIndex.unsafe(0, 0))
    state.addPendingData(block0, broker, origin, ArrayBuffer.empty)
    state.pending.contains(block0.hash) is true
    (0 until cacheSize - 1).foreach { _ =>
      val block1 = block0.copy(header = block0.header.copy(nonce = Nonce.unsecureRandom()))
      state.addPendingData(block1, broker, origin, ArrayBuffer.empty)
      state.pending.contains(block1.hash) is true
    }
    val block1 = block0.copy(header = block0.header.copy(nonce = Nonce.unsecureRandom()))
    state.addPendingData(block1, broker, origin, ArrayBuffer.empty)
    state.pending.contains(block0.hash) is false
  }

  it should "remove pending hashes based on expiry duration" in new Fixture {
    override val configValues = Map(
      ("alephium.broker.broker-num", 1),
      ("alephium.broker.groups", 1),
      ("alephium.network.dependency-expiry-period", "2s")
    )

    config.network.dependencyExpiryPeriod is Duration.ofSecondsUnsafe(2)
    val block0 = mineFromMemPool(blockFlow, ChainIndex.unsafe(0, 0))
    val blocks = (0 until 10).map { _ =>
      val block1 = block0.copy(header = block0.header.copy(nonce = Nonce.unsecureRandom()))
      state.addPendingData(block1, broker, origin, ArrayBuffer.empty)
      state.pending.contains(block1.hash) is true
      block1
    }

    eventually {
      val block1 = block0.copy(header = block0.header.copy(nonce = Nonce.unsecureRandom()))
      state.addPendingData(block1, broker, origin, ArrayBuffer.empty)
      (state.pending.size < state.cacheSize / 2) is true // the cache should be far from being full
      state.pending.contains(block1.hash) is true
      blocks.foreach(block => state.pending.contains(block.hash) is false)
    }
  }

  it should "update dependencies when remove pending flow data" in new Fixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val blockFlow1 = isolatedBlockFlow()
    val blocks = (0 until 3).map { _ =>
      val block = emptyBlock(blockFlow1, chainIndex)
      addAndCheck(blockFlow1, block)
      block
    }
    blocks.foreach { block =>
      state.addPendingData(block, broker, origin, ArrayBuffer.empty)
      state.pending.contains(block.hash)
    }
    state.missing.contains(blocks(0).hash) is false
    state.missing(blocks(1).hash).toSeq is Seq(blocks(0).hash)
    state.missing(blocks(2).hash).toSeq is Seq(blocks(1).hash)
    state.missingIndex(blocks(0).hash).toSeq is Seq(blocks(1).hash)
    state.missingIndex(blocks(1).hash).toSeq is Seq(blocks(2).hash)
    state.missingIndex.contains(blocks(2).hash) is false
    state.readies.toSet is Set(blocks(0).hash)
    state.processing.isEmpty is true

    state.removePending(blocks(1).hash)
    state.pending.contains(blocks(0).hash) is true
    state.pending.contains(blocks(1).hash) is false
    state.pending.contains(blocks(2).hash) is false
    state.missing.contains(blocks(0).hash) is false
    state.missing.contains(blocks(1).hash) is false
    state.missing.contains(blocks(2).hash) is false
    state.missingIndex.contains(blocks(0).hash) is false
    state.missingIndex.contains(blocks(1).hash) is false
    state.missingIndex.contains(blocks(2).hash) is false
    state.readies.toSet is Set(blocks(0).hash)
    state.processing.isEmpty is true
  }

  it should "wait for ghost uncles" in new Fixture {
    val chainIndex   = ChainIndex.unsafe(0, 0)
    val blockFlow0   = isolatedBlockFlow()
    val lockupScript = getGenesisLockupScript(chainIndex.to)

    def mineBlock(parentHash: BlockHash, ghostUncleHashes: AVector[BlockHash]): Block = {
      val template0   = blockFlow0.prepareBlockFlowUnsafe(chainIndex, lockupScript)
      val parentIndex = brokerConfig.groups - 1 + chainIndex.to.value
      val newDeps     = template0.deps.replace(parentIndex, parentHash)
      val uncles =
        ghostUncleHashes.map(hash =>
          SelectedGhostUncle(hash, blockFlow0.getBlockUnsafe(hash).minerLockupScript, 1)
        )
      val template1 = blockFlow
        .rebuild(template0, template0.transactions.init, uncles, lockupScript)
        .copy(
          deps = newDeps,
          depStateHash =
            blockFlow0.getDepStateHash(BlockDeps.unsafe(newDeps), chainIndex.from).rightValue
        )
      mine(blockFlow0, template1)
    }

    addAndCheck(blockFlow0, (0 until 2).map(_ => emptyBlock(blockFlow0, chainIndex)): _*)
    blockFlow0.getMaxHeightByWeight(chainIndex).rightValue is 1
    val hashes = blockFlow0.getHashes(chainIndex, 1).rightValue
    hashes.length is 2
    val (main0, fork0) =
      (blockFlow0.getBlockUnsafe(hashes(0)), blockFlow0.getBlockUnsafe(hashes(1)))
    val main1 = mineBlockTemplate(blockFlow0, chainIndex)
    addAndCheck(blockFlow0, main1)
    main1.parentHash is main0.hash
    main1.ghostUncleHashes.rightValue is AVector(fork0.hash)

    state.addPendingData(main1, broker, origin, ArrayBuffer.empty)
    state.pending.unsafe(main1.hash).extract() is (main1, broker, origin)
    state.missing.get(main1.hash) is Some(ArrayBuffer(main0.hash, fork0.hash))
    state.missingIndex.get(main0.hash) is Some(ArrayBuffer(main1.hash))
    state.missingIndex.get(fork0.hash) is Some(ArrayBuffer(main1.hash))
    state.readies.isEmpty is true
    state.processing.isEmpty is true

    // add block deps
    state.addPendingData(main0, broker, origin, ArrayBuffer.empty)
    state.pending.unsafe(main0.hash).extract() is (main0, broker, origin)
    state.readies is mutable.HashSet(main0.hash)
    state.processing.isEmpty is true
    state.uponDataProcessed(main0)
    state.pending.contains(main0.hash) is false
    state.missingIndex.contains(main0.hash) is false
    state.missingIndex.get(fork0.hash) is Some(ArrayBuffer(main1.hash))
    state.missing.get(main1.hash) is Some(ArrayBuffer(fork0.hash))

    // add ghost uncles
    state.addPendingData(fork0, broker, origin, ArrayBuffer.empty)
    state.pending.unsafe(fork0.hash).extract() is (fork0, broker, origin)
    state.readies is mutable.HashSet(fork0.hash, main0.hash)
    state.processing.isEmpty is true
    state.uponDataProcessed(fork0)
    state.pending.contains(fork0.hash) is false
    state.missingIndex.isEmpty is true
    state.missing.isEmpty is true
    state.readies is mutable.HashSet(main1.hash, fork0.hash, main0.hash)
    state.extractReadies()
    state.readies.isEmpty is true

    addAndCheck(blockFlow, main0, fork0, main1)
    val genesisHash = blockFlow0.getHashes(chainIndex, 0).rightValue.head
    val fork1       = mineBlock(genesisHash, AVector.empty)
    addAndCheck(blockFlow0, fork1)
    val fork2 = mineBlock(main0.hash, AVector(fork1.hash))
    addAndCheck(blockFlow0, fork2)
    val main2 = mineBlock(main1.hash, AVector(fork2.hash))
    addAndCheck(blockFlow0, main2)

    state.addPendingData(main2, broker, origin, ArrayBuffer.empty)
    state.pending.unsafe(main2.hash).extract() is (main2, broker, origin)
    state.missing.get(main2.hash) is Some(ArrayBuffer(fork2.hash))
    state.missingIndex.get(fork2.hash) is Some(ArrayBuffer(main2.hash))
    state.addPendingData(fork2, broker, origin, ArrayBuffer.empty)
    state.pending.unsafe(fork2.hash).extract() is (fork2, broker, origin)
    state.missing.get(fork2.hash) is Some(ArrayBuffer(fork1.hash))
    state.missingIndex.get(fork1.hash) is Some(ArrayBuffer(fork2.hash))
    state.addPendingData(fork1, broker, origin, ArrayBuffer.empty)
    state.pending.unsafe(fork1.hash).extract() is (fork1, broker, origin)
    state.readies is mutable.HashSet(fork1.hash)
    state.uponDataProcessed(fork1)
    state.readies is mutable.HashSet(fork1.hash, fork2.hash)
    state.missing.contains(fork2.hash) is false
    state.missingIndex.contains(fork1.hash) is false
    state.uponDataProcessed(fork2)
    state.readies is mutable.HashSet(fork1.hash, fork2.hash, main2.hash)
    state.missing.isEmpty is true
    state.missingIndex.isEmpty is true
    state.extractReadies()
    state.readies.isEmpty is true
  }

  it should "download missing ghost uncles" in new Fixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val miner      = getGenesisLockupScript(chainIndex.to)

    def mineBlock(ghostUncles: AVector[SelectedGhostUncle]): Block = {
      val template0 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
      val template1 = template0.setGhostUncles(ghostUncles)
      val block     = mine(blockFlow, template1)
      block.ghostUncleHashes.rightValue is ghostUncles.map(_.blockHash)
      block
    }

    {
      val uncleBlock  = emptyBlock(blockFlow, chainIndex)
      val ghostUncles = AVector(SelectedGhostUncle(uncleBlock.hash, miner, 1))
      val block       = mineBlock(ghostUncles)
      brokerProbe.send(
        dependencyHandler,
        DependencyHandler.AddFlowData(AVector(uncleBlock, block), origin)
      )
      brokerProbe.expectNoMessage()
    }

    {
      val uncleBlock = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, uncleBlock)
      val ghostUncles = AVector(SelectedGhostUncle(uncleBlock.hash, miner, 1))
      val block       = mineBlock(ghostUncles)
      brokerProbe.send(dependencyHandler, DependencyHandler.AddFlowData(AVector(block), origin))
      brokerProbe.expectNoMessage()
    }

    {
      val ghostUncles = AVector(SelectedGhostUncle(BlockHash.random, miner, 1))
      val block       = mineBlock(ghostUncles)
      state.readies.add(ghostUncles.head.blockHash)
      brokerProbe.send(dependencyHandler, DependencyHandler.AddFlowData(AVector(block), origin))
      brokerProbe.expectNoMessage()
    }

    {
      val ghostUncles = AVector(SelectedGhostUncle(BlockHash.random, miner, 1))
      val block       = mineBlock(ghostUncles)
      state.missing(ghostUncles.head.blockHash) = ArrayBuffer.empty
      brokerProbe.send(dependencyHandler, DependencyHandler.AddFlowData(AVector(block), origin))
      brokerProbe.expectNoMessage()
    }

    {
      val ghostUncles = AVector(SelectedGhostUncle(BlockHash.random, miner, 1))
      val block       = mineBlock(ghostUncles)
      brokerProbe.send(dependencyHandler, DependencyHandler.AddFlowData(AVector(block), origin))
      eventually(brokerProbe.expectMsg(BrokerHandler.DownloadBlocks(ghostUncles.map(_.blockHash))))
    }
  }

  it should "clean pending block hashes" in new Fixture {
    override val configValues: Map[String, Any] =
      Map(("alephium.network.dependency-expiry-period", "1s"))
    config.network.dependencyExpiryPeriod is Duration.ofSecondsUnsafe(1)

    val chainIndex = ChainIndex.unsafe(0, 0)
    val block0     = emptyBlock(blockFlow, chainIndex)
    state.addPendingData(block0, broker, origin, ArrayBuffer.empty)
    val block1 = emptyBlock(blockFlow, chainIndex)
    val ts     = TimeStamp.now().plusSecondsUnsafe(2)
    state.pending.put(block1.hash, DependencyHandler.PendingStatus(block1, broker, origin, ts))
    state.pending.size is 2

    Thread.sleep(2000)
    state.pending.contains(block0.hash) is false
    state.pending.contains(block1.hash) is true

    Thread.sleep(2000)
    state.pending.isEmpty is true
  }

  it should "publish the FlowDataAlreadyExist event" in new Fixture {
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[DependencyHandler.FlowDataAlreadyExist])

    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = emptyBlock(blockFlow, chainIndex)
    state.pending.contains(block.hash) is false
    brokerProbe.send(dependencyHandler, DependencyHandler.AddFlowData(AVector(block), origin))
    eventually {
      brokerProbe.expectNoMessage()
      listener.expectNoMessage()
      state.pending.contains(block.hash) is true
    }

    addAndCheck(blockFlow, block)
    dependencyHandler ! ChainHandler.FlowDataAdded(block, origin, TimeStamp.now())
    eventually(state.pending.contains(block.hash) is false)

    brokerProbe.send(dependencyHandler, DependencyHandler.AddFlowData(AVector(block), origin))
    eventually {
      brokerProbe.expectNoMessage()
      listener.expectMsg(DependencyHandler.FlowDataAlreadyExist(block))
    }
  }
}
