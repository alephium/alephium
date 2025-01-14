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

package org.alephium.flow.network.sync

import scala.collection.mutable

import akka.actor.PoisonPill
import akka.testkit.{EventFilter, TestActorRef, TestProbe}

import org.alephium.flow.FlowFixture
import org.alephium.flow.handler.{ChainHandler, DependencyHandler, FlowHandler, TestUtils}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.network.broker.{
  BrokerHandler,
  ChainTipInfo,
  InboundConnection,
  MisbehaviorManager
}
import org.alephium.protocol.Generators
import org.alephium.protocol.message.{ProtocolV1, ProtocolV2, ProtocolVersion}
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector, TimeStamp}

// scalastyle:off file.size.limit
class BlockFlowSynchronizerSpec extends AlephiumActorSpec {
  import BrokerStatusTracker._

  override def actorSystemConfig = AlephiumActorSpec.debugConfig

  trait Fixture extends FlowFixture with Generators {
    lazy val (allHandlers, allProbes) = TestUtils.createAllHandlersProbe
    lazy val blockFlowSynchronizer = TestActorRef[BlockFlowSynchronizer](
      BlockFlowSynchronizer.props(blockFlow, allHandlers)
    )
    lazy val blockFlowSynchronizerActor = blockFlowSynchronizer.underlyingActor

    def blockProcessed(block: Block): Unit = {
      blockFlowSynchronizer ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    }
  }

  it should "add/remove brokers" in new Fixture {
    blockFlowSynchronizerActor.brokers.isEmpty is true

    val probe  = TestProbe()
    val broker = brokerInfoGen.sample.get
    probe.send(
      blockFlowSynchronizer,
      InterCliqueManager.HandShaked(probe.ref, broker, InboundConnection, "", ProtocolV1)
    )
    eventually(blockFlowSynchronizerActor.brokers.toMap.contains(probe.ref) is true)

    system.stop(probe.ref)
    eventually(blockFlowSynchronizerActor.brokers.isEmpty is true)
  }

  it should "handle block announcement" in new Fixture {
    val broker     = TestProbe()
    val brokerInfo = brokerInfoGen.sample.get
    val blockHash  = BlockHash.generate

    broker.send(
      blockFlowSynchronizer,
      InterCliqueManager.HandShaked(broker.ref, brokerInfo, InboundConnection, "", ProtocolV1)
    )
    eventually(blockFlowSynchronizerActor.brokers.toMap.contains(broker.ref) is true)
    broker.send(blockFlowSynchronizer, BlockFlowSynchronizer.BlockAnnouncement(blockHash))
    broker.expectMsg(BrokerHandler.DownloadBlocks(AVector(blockHash)))
    eventually(blockFlowSynchronizerActor.fetching.states.contains(blockHash) is true)
  }

  behavior of "BlockFlowSynchronizerV1"

  it should "cleanup expired downloading accordingly" in new Fixture {
    blockFlowSynchronizerActor.switchToV1()

    val now   = TimeStamp.now()
    val hash0 = BlockHash.generate
    val hash1 = BlockHash.generate
    blockFlowSynchronizerActor.syncing.addOne(
      (hash0, now.minusUnsafe(networkConfig.syncExpiryPeriod.timesUnsafe(2)))
    )
    blockFlowSynchronizerActor.syncing.addOne((hash1, now))
    blockFlowSynchronizer ! BlockFlowSynchronizer.CleanDownloading
    blockFlowSynchronizerActor.syncing.size is 1
    blockFlowSynchronizerActor.syncing.contains(hash0) is false
    blockFlowSynchronizerActor.syncing.contains(hash1) is true
  }

  it should "download blocks by inventories" in new Fixture {
    blockFlowSynchronizerActor.switchToV1()

    val now   = TimeStamp.now()
    val hash0 = BlockHash.generate
    val hash1 = BlockHash.generate
    blockFlowSynchronizerActor.syncing.addOne((hash0, now))
    blockFlowSynchronizer ! BlockFlowSynchronizer.SyncInventories(AVector(AVector(hash0, hash1)))
    expectMsg(BrokerHandler.DownloadBlocks(AVector(hash1)))

    blockFlowSynchronizer ! BlockFlowSynchronizer.SyncInventories(AVector(AVector(hash0, hash1)))
    expectNoMessage()
  }

  it should "handle finalized blocks" in new Fixture {
    blockFlowSynchronizerActor.switchToV1()

    val block = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    blockFlowSynchronizerActor.syncing.addOne((block.hash, TimeStamp.now()))
    blockProcessed(block)
    blockFlowSynchronizerActor.syncing.isEmpty is true
  }

  behavior of "BlockFlowSynchronizerV2"

  trait BlockFlowSynchronizerV2Fixture extends Fixture {
    import SyncState._

    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    def addBroker(version: ProtocolVersion = ProtocolV2): (BrokerActor, BrokerStatus, TestProbe) = {
      val brokerInfo =
        BrokerInfo.unsafe(CliqueId.generate, 0, 1, socketAddressGen.sample.get)
      val probe                    = TestProbe()
      val brokerActor: BrokerActor = ActorRefT(probe.ref)
      probe.send(
        blockFlowSynchronizer,
        InterCliqueManager.HandShaked(probe.ref, brokerInfo, InboundConnection, "", version)
      )
      val brokerStatus = blockFlowSynchronizerActor.getBrokerStatus(brokerActor).get
      brokerStatus.tips.length is brokerConfig.chainNum
      brokerStatus.tips.foreach(_.isEmpty is true)
      (brokerActor, brokerStatus, probe)
    }

    @scala.annotation.tailrec
    final def genBlockHash(chainIndex: ChainIndex): BlockHash = {
      val blockHash = BlockHash.generate
      if (ChainIndex.from(blockHash) == chainIndex) blockHash else genBlockHash(chainIndex)
    }

    def genChainTips: AVector[ChainTip] = {
      brokerConfig.chainIndexes.map { chainIndex =>
        val blockHash = genBlockHash(chainIndex)
        chainTipGen.sample.get.copy(hash = blockHash)
      }
    }

    def addSyncingChain(chainIndex: ChainIndex, tipHeight: Int, originPeer: BrokerActor) = {
      val bestTip = chainTipGen.sample.get.copy(hash = genBlockHash(chainIndex), height = tipHeight)
      blockFlowSynchronizerActor.getBrokerStatus(originPeer).foreach(_.updateTips(AVector(bestTip)))
      val syncState = SyncStatePerChain(chainIndex, bestTip, originPeer)
      blockFlowSynchronizerActor.syncingChains(chainIndex) = syncState
      syncState
    }

    def addSyncingChain(chain: SyncStatePerChain) = {
      blockFlowSynchronizerActor
        .getBrokerStatus(chain.originBroker)
        .foreach(_.updateTips(AVector(chain.bestTip)))
      blockFlowSynchronizerActor.syncingChains(chain.chainIndex) = chain
    }

    def genTasks(chainIndex: ChainIndex, size: Int) = {
      val toHeader = emptyBlock(blockFlow, chainIndex).header
      AVector.from(0 until size).map { index =>
        val fromHeight = BatchSize * index + 1
        val toHeight   = BatchSize * (index + 1)
        BlockDownloadTask(chainIndex, fromHeight, toHeight, Some(toHeader))
      }
    }
  }

  it should "schedule sync" in new BlockFlowSynchronizerV2Fixture {
    addBroker()
    blockFlowSynchronizer ! BlockFlowSynchronizer.Sync
    allProbes.flowHandler.expectMsg(FlowHandler.GetChainState)
  }

  it should "handle self chain state" in new BlockFlowSynchronizerV2Fixture {
    val (_, _, probe) = addBroker()
    val chainTips     = genChainTips
    blockFlowSynchronizerActor.selfChainTips is None
    blockFlowSynchronizerActor.isSyncing is false
    blockFlowSynchronizer ! FlowHandler.ChainState(chainTips)
    blockFlowSynchronizerActor.selfChainTips is Some(chainTips)
    blockFlowSynchronizerActor.isSyncing is false
    probe.expectMsg(BrokerHandler.SendChainState(chainTips))
  }

  it should "handle peer chain state" in new BlockFlowSynchronizerV2Fixture {
    val (brokerActor0, brokerStatus0, _) = addBroker()
    val chainTips0                       = genChainTips
    blockFlowSynchronizer.tell(BlockFlowSynchronizer.UpdateChainState(chainTips0), brokerActor0.ref)
    brokerConfig.chainIndexes.foreach { chainIndex =>
      val index = chainIndex.from.value * brokerConfig.groups + chainIndex.to.value
      blockFlowSynchronizerActor.bestChainTips(chainIndex) is (brokerActor0, chainTips0(index))
    }
    brokerStatus0.tips is Array.from(chainTips0.map(Option.apply))

    val (brokerActor1, brokerStatus1, _) = addBroker()
    val chainTips1                       = genChainTips
    blockFlowSynchronizer.tell(BlockFlowSynchronizer.UpdateChainState(chainTips1), brokerActor1.ref)
    brokerConfig.chainIndexes.foreach { chainIndex =>
      val index     = chainIndex.flattenIndex
      val chainTip0 = chainTips0(index)
      val chainTip1 = chainTips1(index)
      if (chainTip1.weight > chainTip0.weight) {
        blockFlowSynchronizerActor.bestChainTips(chainIndex) is (brokerActor1, chainTip1)
      } else {
        blockFlowSynchronizerActor.bestChainTips(chainIndex) is (brokerActor0, chainTip0)
      }
    }
    brokerStatus1.tips is Array.from(chainTips1.map(Option.apply))
  }

  it should "handle self chain state and start syncing" in new BlockFlowSynchronizerV2Fixture {
    val (brokerActor, _, probe) = addBroker()
    val selfChainTips           = genChainTips
    val bestChainTips = selfChainTips.map(tip => tip.copy(weight = tip.weight + Weight(1)))

    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateChainState(bestChainTips),
      brokerActor.ref
    )
    blockFlowSynchronizerActor.isSyncing is false
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)
    probe.expectMsg(BrokerHandler.SendChainState(selfChainTips))

    blockFlowSynchronizerActor.syncingChains.size is brokerConfig.chainIndexes.length
    brokerConfig.chainIndexes.foreach { chainIndex =>
      val index     = chainIndex.from.value * brokerConfig.groups + chainIndex.to.value
      val syncState = blockFlowSynchronizerActor.syncingChains(chainIndex)
      syncState.chainIndex is chainIndex
      syncState.originBroker is brokerActor
      syncState.bestTip is bestChainTips(index)
    }

    val request = brokerConfig.chainIndexes.map { chainIndex =>
      val index   = chainIndex.from.value * brokerConfig.groups + chainIndex.to.value
      val selfTip = selfChainTips(index)
      val bestTip = bestChainTips(index)
      ChainTipInfo(chainIndex, bestTip, selfTip)
    }
    probe.expectMsg(BrokerHandler.GetAncestors(request))
  }

  it should "only sync those chains that need to be synchronized" in new BlockFlowSynchronizerV2Fixture {
    val (brokerActor, _, probe) = addBroker()
    val selfChainTips           = genChainTips
    val selfChainTip            = selfChainTips(0)
    val bestChainTip            = selfChainTip.copy(weight = selfChainTip.weight + Weight(1))
    val bestChainTips           = selfChainTips.replace(0, bestChainTip)

    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateChainState(bestChainTips),
      brokerActor.ref
    )
    blockFlowSynchronizerActor.isSyncing is false
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)
    probe.expectMsg(BrokerHandler.SendChainState(selfChainTips))

    val chainIndex = ChainIndex.unsafe(0, 0)
    blockFlowSynchronizerActor.syncingChains.size is 1
    val syncState = blockFlowSynchronizerActor.syncingChains(chainIndex)
    syncState.chainIndex is chainIndex
    syncState.originBroker is brokerActor
    syncState.bestTip is bestChainTip
    probe.expectMsg(
      BrokerHandler.GetAncestors(AVector(ChainTipInfo(chainIndex, bestChainTip, selfChainTip)))
    )
  }

  it should "not start syncing if self chain tip better than peers" in new BlockFlowSynchronizerV2Fixture {
    val (brokerActor, _, probe) = addBroker()
    val bestChainTips           = genChainTips
    val selfChainTips = bestChainTips.map(tip => tip.copy(weight = tip.weight + Weight(1)))

    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateChainState(bestChainTips),
      brokerActor.ref
    )
    blockFlowSynchronizerActor.isSyncing is false
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)
    probe.expectMsg(BrokerHandler.SendChainState(selfChainTips))
    blockFlowSynchronizerActor.isSyncing is false
    probe.expectNoMessage()
  }

  it should "start syncing from multiple peers" in new BlockFlowSynchronizerV2Fixture {
    val (brokerActor0, _, probe0) = addBroker()
    val (brokerActor1, _, probe1) = addBroker()

    val selfChainTips  = genChainTips
    val selfChainTip0  = selfChainTips(0)
    val selfChainTip1  = selfChainTips(1)
    val bestChainTip0  = selfChainTip0.copy(weight = selfChainTip0.weight + Weight(1))
    val bestChainTip1  = selfChainTip1.copy(weight = selfChainTip1.weight + Weight(1))
    val bestChainTips0 = selfChainTips.replace(0, bestChainTip0)
    val bestChainTips1 = selfChainTips.replace(1, bestChainTip1)

    probe0.ignoreMsg { case _: BrokerHandler.SendChainState => true }
    probe1.ignoreMsg { case _: BrokerHandler.SendChainState => true }
    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateChainState(bestChainTips0),
      brokerActor0.ref
    )
    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateChainState(bestChainTips1),
      brokerActor1.ref
    )
    blockFlowSynchronizerActor.isSyncing is false
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)
    blockFlowSynchronizerActor.isSyncing is true

    val chainIndex0 = ChainIndex.unsafe(0, 0)
    val chainIndex1 = ChainIndex.unsafe(0, 1)
    blockFlowSynchronizerActor.syncingChains.size is 2
    val syncState0 = blockFlowSynchronizerActor.syncingChains(chainIndex0)
    syncState0.chainIndex is chainIndex0
    syncState0.originBroker is brokerActor0
    syncState0.bestTip is bestChainTip0
    val syncState1 = blockFlowSynchronizerActor.syncingChains(chainIndex1)
    syncState1.chainIndex is chainIndex1
    syncState1.originBroker is brokerActor1
    syncState1.bestTip is bestChainTip1
    probe0.expectMsg(
      BrokerHandler.GetAncestors(AVector(ChainTipInfo(chainIndex0, bestChainTip0, selfChainTip0)))
    )
    probe1.expectMsg(
      BrokerHandler.GetAncestors(AVector(ChainTipInfo(chainIndex1, bestChainTip1, selfChainTip1)))
    )
  }

  it should "download latest blocks from the origin broker" in new BlockFlowSynchronizerV2Fixture {
    import SyncState._

    val (brokerActor0, brokerStatus0, probe0) = addBroker()
    val (_, brokerStatus1, probe1)            = addBroker()
    val chainIndex                            = ChainIndex.unsafe(0, 0)

    blockFlowSynchronizerActor.isSyncing = true
    val syncingChain = addSyncingChain(chainIndex, 200, brokerActor0)
    syncingChain.nextFromHeight = 191
    brokerStatus1.updateTips(AVector(syncingChain.bestTip))

    val selfChainTip = syncingChain.bestTip.copy(weight =
      Weight(syncingChain.bestTip.weight.value.subtract(BigInt(1)))
    )
    val selfChainTips = genChainTips.replace(0, selfChainTip)
    probe0.ignoreMsg { case _: BrokerHandler.SendChainState => true }
    probe1.ignoreMsg { case _: BrokerHandler.SendChainState => true }
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)

    val task = BlockDownloadTask(chainIndex, 191, 200, None)
    brokerStatus0.canDownload(task) is true
    brokerStatus1.canDownload(task) is true

    blockProcessed(emptyBlock(blockFlow, chainIndex))
    probe0.expectMsg(BrokerHandler.DownloadBlockTasks(AVector(task)))
    probe1.expectNoMessage()

    brokerStatus0.requestNum = MaxRequestNum
    brokerStatus0.canDownload(task) is false
    brokerStatus1.canDownload(task) is true
    syncingChain.nextFromHeight = 191
    blockProcessed(emptyBlock(blockFlow, chainIndex))
    probe0.expectNoMessage()
    probe1.expectNoMessage()
  }

  it should "try to resync if the sync is completed" in new BlockFlowSynchronizerV2Fixture {
    val (brokerActor, _, _) = addBroker()
    val chainIndex          = ChainIndex.unsafe(0, 0)
    blockFlowSynchronizerActor.isSyncing = true
    val syncingChain = addSyncingChain(chainIndex, 200, brokerActor)
    val selfChainTip = syncingChain.bestTip.copy(weight = syncingChain.bestTip.weight + Weight(1))
    blockFlowSynchronizer ! FlowHandler.ChainState(genChainTips.replace(0, selfChainTip))
    EventFilter.debug(start = "Clear syncing state and resync", occurrences = 1).intercept {
      blockProcessed(emptyBlock(blockFlow, chainIndex))
    }
    blockFlowSynchronizerActor.isSyncing is false
    blockFlowSynchronizerActor.syncingChains.isEmpty is true
  }

  it should "try move on" in new BlockFlowSynchronizerV2Fixture {
    val (brokerActor, _, probe) = addBroker()
    val chainIndex              = ChainIndex.unsafe(0, 0)
    blockFlowSynchronizerActor.isSyncing = true
    val syncingChain = addSyncingChain(chainIndex, 200, brokerActor)
    syncingChain.nextFromHeight = 1

    val selfChainTip = syncingChain.bestTip.copy(weight =
      Weight(syncingChain.bestTip.weight.value.subtract(BigInt(1)))
    )
    val selfChainTips = genChainTips.replace(0, selfChainTip)
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)
    blockProcessed(emptyBlock(blockFlow, chainIndex))
    probe.expectMsg(BrokerHandler.SendChainState(selfChainTips))
    syncingChain.skeletonHeightRange is Some(BlockHeightRange.from(50, 200, 50))
    syncingChain.nextFromHeight is 201
    probe.expectMsg(
      BrokerHandler.GetSkeletons(AVector((chainIndex, syncingChain.skeletonHeightRange.get)))
    )
  }

  it should "handle ancestors response" in new BlockFlowSynchronizerV2Fixture {
    import SyncState._

    val (brokerActor0, _, probe0) = addBroker()
    val (brokerActor1, _, probe1) = addBroker()
    val chainIndex0               = ChainIndex.unsafe(0, 0)
    val chainIndex1               = ChainIndex.unsafe(0, 1)
    addSyncingChain(chainIndex0, BatchSize, brokerActor0)
    addSyncingChain(chainIndex1, BatchSize + 1, brokerActor1)

    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateAncestors(AVector((chainIndex0, 0))),
      brokerActor0.ref
    )
    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateAncestors(AVector((chainIndex1, 0))),
      brokerActor1.ref
    )
    val task = BlockDownloadTask(chainIndex0, 1, BatchSize, None)
    probe0.expectMsg(BrokerHandler.DownloadBlockTasks(AVector(task)))
    val range = BlockHeightRange.from(BatchSize, BatchSize, BatchSize)
    probe1.expectMsg(BrokerHandler.GetSkeletons(AVector((chainIndex1, range))))
  }

  it should "handle skeleton response" in new BlockFlowSynchronizerV2Fixture {
    import SyncState._

    val (brokerActor, _, probe) = addBroker()
    val chainIndex              = ChainIndex.unsafe(0, 0)
    val syncingChain            = addSyncingChain(chainIndex, 200, brokerActor)
    val heights                 = BlockHeightRange.from(50, 100, 50)
    val headers = AVector.fill(heights.length)(emptyBlock(blockFlow, chainIndex).header)
    val response =
      BlockFlowSynchronizer.UpdateSkeletons(AVector((chainIndex, heights)), AVector(headers))
    blockFlowSynchronizer.tell(response, TestProbe().ref)
    probe.expectNoMessage()

    syncingChain.skeletonHeightRange = Some(heights)
    blockFlowSynchronizer.tell(response, TestProbe().ref)
    syncingChain.skeletonHeightRange.isDefined is true
    probe.expectNoMessage()

    blockFlowSynchronizer.tell(response, brokerActor.ref)
    syncingChain.skeletonHeightRange.isDefined is false
    val tasks = AVector(
      BlockDownloadTask(chainIndex, 1, 50, Some(headers(0))),
      BlockDownloadTask(chainIndex, 51, 100, Some(headers(1)))
    )
    syncingChain.taskIds.toSet is tasks.map(_.id).toSet
    probe.expectMsg(BrokerHandler.DownloadBlockTasks(tasks))
  }

  it should "handle downloaded blocks" in new BlockFlowSynchronizerV2Fixture {
    import SyncState._

    val (brokerActor, brokerStatus, _) = addBroker()
    val dataOrigin                     = DataOrigin.InterClique(brokerStatus.info)
    val chainIndex                     = ChainIndex.unsafe(0, 0)
    blockFlowSynchronizerActor.isSyncing = true
    val syncingChain = addSyncingChain(chainIndex, 200, brokerActor)

    val invalidTask = BlockDownloadTask(chainIndex, 21, 70, None)
    val task0       = BlockDownloadTask(chainIndex, 1, 50, None)
    val task1       = BlockDownloadTask(chainIndex, 51, 100, None)
    val blocks0     = AVector(emptyBlock(blockFlow, chainIndex))
    val blocks1     = AVector(emptyBlock(blockFlow, chainIndex))
    syncingChain.taskIds.addAll(Seq(task0.id, task1.id))
    brokerStatus.requestNum is 0
    brokerStatus.pendingTasks.isEmpty is true
    brokerStatus.addPendingTask(task0)
    brokerStatus.addPendingTask(task1)
    brokerStatus.requestNum is 100
    brokerStatus.pendingTasks.size is 2

    syncingChain.downloadedBlocks.isEmpty is true
    syncingChain.blockQueue.isEmpty is true
    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateBlockDownloaded(AVector((invalidTask, blocks0, true))),
      brokerActor.ref
    )
    syncingChain.downloadedBlocks.isEmpty is true
    syncingChain.blockQueue.isEmpty is true

    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateBlockDownloaded(AVector((task1, blocks1, true))),
      brokerActor.ref
    )
    syncingChain.downloadedBlocks.keys.toSet is Set(task1.id)
    syncingChain.blockQueue.isEmpty is true
    allProbes.dependencyHandler.expectNoMessage()

    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateBlockDownloaded(AVector((task0, blocks0, true))),
      brokerActor.ref
    )
    syncingChain.downloadedBlocks.isEmpty is true
    syncingChain.taskIds.isEmpty is true
    allProbes.dependencyHandler.expectMsg(
      DependencyHandler.AddFlowData(blocks0 ++ blocks1, dataOrigin)
    )
    syncingChain.blockQueue.isEmpty is true
    syncingChain.validating.toSet is Set(blocks0.head.hash, blocks1.head.hash)
    brokerStatus.requestNum is 0
    brokerStatus.pendingTasks.isEmpty is true
  }

  it should "handle finalized blocks and validate more blocks" in new BlockFlowSynchronizerV2Fixture {
    import SyncState._

    val (brokerActor, brokerStatus, _) = addBroker()
    val dataOrigin                     = DataOrigin.InterClique(brokerStatus.info)
    val chainIndex                     = ChainIndex.unsafe(0, 0)
    blockFlowSynchronizerActor.isSyncing = true

    val syncingChain = addSyncingChain(chainIndex, 200, brokerActor)
    val selfChainTip = syncingChain.bestTip.copy(weight =
      Weight(syncingChain.bestTip.weight.value.subtract(BigInt(1)))
    )
    blockFlowSynchronizer ! FlowHandler.ChainState(genChainTips.replace(0, selfChainTip))
    val blocks = AVector.fill(3)(emptyBlock(blockFlow, chainIndex))
    val block  = blocks(1)
    syncingChain.blockQueue.contains(block.hash) is false
    syncingChain.validating.addOne(block.hash)

    val remainBlocks = AVector(blocks(0), blocks(2))
    remainBlocks.foreach { block =>
      val downloadedBlock = DownloadedBlock(block, (brokerActor, brokerStatus.info))
      syncingChain.blockQueue.addOne((block.hash, downloadedBlock))
    }

    blockProcessed(block)
    syncingChain.validating.contains(block.hash) is false
    syncingChain.blockQueue.contains(block.hash) is false

    remainBlocks.foreach { block =>
      syncingChain.validating.contains(block.hash) is true
      syncingChain.blockQueue.contains(block.hash) is false
    }
    allProbes.dependencyHandler.expectMsg(DependencyHandler.AddFlowData(remainBlocks, dataOrigin))

    remainBlocks.foreach(blockProcessed)
    syncingChain.validating.isEmpty is true
    syncingChain.blockQueue.isEmpty is true
    allProbes.dependencyHandler.expectNoMessage()
  }

  it should "download blocks from multiple peers" in new BlockFlowSynchronizerV2Fixture {
    override val configValues: Map[String, Any] =
      Map(("alephium.broker.broker-num", 1), ("alephium.broker.groups", 4))

    val (brokerActor0, brokerStatus0, probe0) = addBroker()
    val (brokerActor1, brokerStatus1, probe1) = addBroker()
    val allTasks = brokerConfig.chainIndexes.map { chainIndex =>
      val syncingChain  = addSyncingChain(chainIndex, Int.MaxValue, brokerActor0)
      val tasksPerChain = genTasks(chainIndex, 16)
      syncingChain.taskQueue.addAll(tasksPerChain)
      tasksPerChain
    }
    val chainTips = genChainTips.map(tip => tip.copy(height = Int.MaxValue))
    brokerStatus0.updateTips(chainTips)
    brokerStatus1.updateTips(chainTips)

    blockFlowSynchronizerActor.downloadBlocks()
    val broker0Tasks = AVector.tabulate(allTasks.length)(index => allTasks(index)(0))
    val broker1Tasks = AVector.tabulate(allTasks.length)(index => allTasks(index)(1))
    probe0.expectMsgPF() { case BrokerHandler.DownloadBlockTasks(tasks) =>
      tasks.toSet is broker0Tasks.toSet
    }
    probe1.expectMsgPF() { case BrokerHandler.DownloadBlockTasks(tasks) =>
      tasks.toSet is broker1Tasks.toSet
    }

    val downloadedBlocks = broker1Tasks.map(task => (task, AVector.empty[Block], true))
    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateBlockDownloaded(downloadedBlocks),
      brokerActor1.ref
    )
    probe1.expectMsgPF() { case BrokerHandler.DownloadBlockTasks(tasks) =>
      tasks.toSet is AVector.tabulate(allTasks.length)(index => allTasks(index)(2)).toSet
    }
  }

  it should "collect tasks in order of task size" in new BlockFlowSynchronizerV2Fixture {
    import SyncState._

    override val configValues: Map[String, Any] =
      Map(("alephium.broker.broker-num", 1), ("alephium.broker.groups", 4))

    val (brokerActor, brokerStatus, probe) = addBroker()
    val maxTaskSize                        = 20
    val allChains = brokerConfig.chainIndexes.mapWithIndex { case (chainIndex, index) =>
      val syncingChain  = SyncStatePerChain(chainIndex, chainTipGen.sample.get, brokerActor)
      val tasksPerChain = genTasks(chainIndex, maxTaskSize).dropRight(index)
      syncingChain.taskQueue.addAll(tasksPerChain)
      (syncingChain, tasksPerChain)
    }
    allChains.shuffle().foreach { case (chain, _) => addSyncingChain(chain) }
    val chainTips = genChainTips.map(tip => tip.copy(height = Int.MaxValue))
    brokerStatus.updateTips(chainTips)

    val allTasks = allChains.sortBy(_._2.length).reverse.map(_._2.head)
    blockFlowSynchronizerActor.downloadBlocks()
    probe.expectMsgPF() { case BrokerHandler.DownloadBlockTasks(tasks) => tasks is allTasks }
  }

  it should "collect tasks in order of broker capacity" in new BlockFlowSynchronizerV2Fixture {
    import SyncState._

    val brokers      = AVector.from((0 until 4).map(_ => addBroker()))
    val chainIndex   = ChainIndex.unsafe(0, 0)
    val syncingChain = addSyncingChain(chainIndex, Int.MaxValue, brokers.head._1)
    val allTasks     = genTasks(chainIndex, 16)
    syncingChain.taskQueue.addAll(allTasks)

    val chainTips = genChainTips.map(tip => tip.copy(height = Int.MaxValue))
    brokers.shuffle().foreachWithIndex { case ((_, status, _), index) =>
      status.requestNum = MaxRequestNum - (BatchSize * (index + 1))
      status.updateTips(chainTips)
    }

    val probes = brokers.sortBy(_._2.requestNum).map(_._3)
    blockFlowSynchronizerActor.downloadBlocks()
    val tasksPerBroker = AVector(
      allTasks.slice(0, 4),
      allTasks.slice(4, 7),
      allTasks.slice(7, 9),
      allTasks.slice(9, 10)
    )
    probes.foreachWithIndex { case (probe, index) =>
      probe.expectMsgPF() { case BrokerHandler.DownloadBlockTasks(tasks) =>
        tasks is tasksPerBroker(index)
      }
    }
  }

  it should "handle missed blocks" in new BlockFlowSynchronizerV2Fixture {
    import SyncState._

    val chainIndex                            = ChainIndex.unsafe(0, 0)
    val (brokerActor0, brokerStatus0, probe0) = addBroker()
    val (brokerActor1, brokerStatus1, probe1) = addBroker()
    val syncingChain = addSyncingChain(chainIndex, Int.MaxValue, brokerActor0)
    val task = BlockDownloadTask(chainIndex, 1, 50, Some(emptyBlock(blockFlow, chainIndex).header))

    syncingChain.taskIds.addOne(task.id)
    brokerStatus1.updateTips(AVector(syncingChain.bestTip))
    syncingChain.taskQueue.addOne(task)
    blockFlowSynchronizerActor.downloadBlocks()
    probe0.expectMsg(BrokerHandler.DownloadBlockTasks(AVector(task)))
    syncingChain.taskQueue.isEmpty is true

    brokerStatus0.missedBlocks.isEmpty is true
    brokerStatus1.missedBlocks.isEmpty is true
    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateBlockDownloaded(AVector((task, AVector.empty, false))),
      brokerActor0.ref
    )
    brokerStatus0.missedBlocks.size is 1
    brokerStatus0.missedBlocks(chainIndex).toSet is Set(task.id)
    brokerStatus1.missedBlocks.isEmpty is true
    probe1.expectMsg(BrokerHandler.DownloadBlockTasks(AVector(task)))

    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateBlockDownloaded(AVector((task, AVector.empty, true))),
      brokerActor1.ref
    )
    syncingChain.isSkeletonFilled is true
    brokerStatus0.missedBlocks.isEmpty is true
  }

  it should "resync if the origin peer is bad" in new BlockFlowSynchronizerV2Fixture {
    import SyncState._

    val chainIndex                            = ChainIndex.unsafe(0, 0)
    val (brokerActor0, brokerStatus0, probe0) = addBroker()
    val (brokerActor1, brokerStatus1, probe1) = addBroker()
    val selfChainTips                         = genChainTips
    val bestChainTips =
      selfChainTips.replace(0, selfChainTips(0).copy(weight = selfChainTips(0).weight + Weight(1)))
    val syncingChain = addSyncingChain(chainIndex, Int.MaxValue, brokerActor0)
    val task = BlockDownloadTask(chainIndex, 1, 50, Some(emptyBlock(blockFlow, chainIndex).header))

    syncingChain.taskIds.addOne(task.id)
    brokerStatus0.updateTips(bestChainTips)
    brokerStatus1.updateTips(bestChainTips)
    blockFlowSynchronizerActor.isSyncing = true
    blockFlowSynchronizerActor.selfChainTips = Some(selfChainTips)
    syncingChain.taskQueue.addOne(task)
    blockFlowSynchronizerActor.downloadBlocks()
    probe0.expectMsg(BrokerHandler.DownloadBlockTasks(AVector(task)))
    syncingChain.taskQueue.isEmpty is true
    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateChainState(bestChainTips),
      brokerActor0.ref
    )

    blockFlowSynchronizer.tell(
      BlockFlowSynchronizer.UpdateBlockDownloaded(AVector((task, AVector.empty, false))),
      brokerActor0.ref
    )
    probe1.expectMsg(BrokerHandler.DownloadBlockTasks(AVector(task)))

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    watch(brokerActor0.ref)

    EventFilter.debug(start = "Clear syncing state and resync", occurrences = 1).intercept {
      blockFlowSynchronizer.tell(
        BlockFlowSynchronizer.UpdateBlockDownloaded(AVector((task, AVector.empty, false))),
        brokerActor1.ref
      )
    }

    listener.expectMsg(MisbehaviorManager.InvalidFlowData(brokerStatus0.info.address))
    expectTerminated(brokerActor0.ref)
    syncingChain.isSkeletonFilled is false
    syncingChain.taskQueue.head is task
    blockFlowSynchronizerActor.getBrokerStatus(brokerActor0).isEmpty is true
    blockFlowSynchronizerActor.getBrokerStatus(brokerActor1).isDefined is true
    blockFlowSynchronizerActor.syncingChains(chainIndex).originBroker is brokerActor1
  }

  it should "resync if the origin peer is terminated" in new BlockFlowSynchronizerV2Fixture {
    val chainIndex                = ChainIndex.unsafe(0, 0)
    val (brokerActor0, _, _)      = addBroker()
    val (brokerActor1, _, probe1) = addBroker()
    val chainTips0                = genChainTips
    val chainTips1 =
      chainTips0.replace(0, chainTips0(0).copy(weight = chainTips0(0).weight + Weight(1)))
    blockFlowSynchronizer.tell(BlockFlowSynchronizer.UpdateChainState(chainTips0), brokerActor0.ref)
    blockFlowSynchronizer.tell(BlockFlowSynchronizer.UpdateChainState(chainTips1), brokerActor1.ref)

    val selfTips = chainTips0.replace(
      0,
      chainTips0(0).copy(weight = Weight(chainTips0(0).weight.value - BigInt(1)))
    )
    blockFlowSynchronizerActor.isSyncing is false
    blockFlowSynchronizer ! FlowHandler.ChainState(selfTips)
    blockFlowSynchronizerActor.isSyncing is true
    blockFlowSynchronizerActor.syncingChains(chainIndex).originBroker is brokerActor1
    blockFlowSynchronizerActor.bestChainTips(chainIndex)._1 is brokerActor1

    probe1.ref ! PoisonPill
    eventually {
      blockFlowSynchronizerActor.getBrokerStatus(brokerActor1).isEmpty is true
      blockFlowSynchronizerActor.syncingChains(chainIndex).originBroker is brokerActor0
      blockFlowSynchronizerActor.bestChainTips(chainIndex)._1 is brokerActor0
    }
  }

  it should "reschedule download tasks if the peer is terminated" in new BlockFlowSynchronizerV2Fixture {
    import SyncState._

    val chainIndex                 = ChainIndex.unsafe(0, 0)
    val (_, brokerStatus0, probe0) = addBroker()
    val (brokerActor1, _, probe1)  = addBroker()
    val syncingChain               = addSyncingChain(chainIndex, Int.MaxValue, brokerActor1)
    brokerStatus0.updateTips(AVector(syncingChain.bestTip))
    blockFlowSynchronizerActor.isSyncing = true

    val task = BlockDownloadTask(chainIndex, 1, 50, Some(emptyBlock(blockFlow, chainIndex).header))
    syncingChain.taskQueue.addOne(task)
    syncingChain.taskIds.addOne(task.id)
    blockFlowSynchronizerActor.downloadBlocks()
    probe0.expectMsg(BrokerHandler.DownloadBlockTasks(AVector(task)))
    probe1.expectNoMessage()

    probe0.ref ! PoisonPill
    eventually(probe1.expectMsg(BrokerHandler.DownloadBlockTasks(AVector(task))))
  }

  it should "switch between V1 and V2" in new BlockFlowSynchronizerV2Fixture {
    import SyncState.FallbackThreshold

    val selfChainTips = genChainTips
    blockFlowSynchronizerActor.currentVersion is ProtocolV1
    addBroker(ProtocolV2)
    blockFlowSynchronizerActor.startTime.isDefined is false
    blockFlowSynchronizerActor.isSyncing is false
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)
    blockFlowSynchronizerActor.currentVersion is ProtocolV2
    blockFlowSynchronizerActor.startTime.isDefined is true
    blockFlowSynchronizerActor.isSyncing is false

    blockFlowSynchronizerActor.startTime =
      Some(TimeStamp.now().minusUnsafe(FallbackThreshold.timesUnsafe(2)))
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)
    blockFlowSynchronizerActor.currentVersion is ProtocolV1
    blockFlowSynchronizerActor.startTime.isEmpty is true
    blockFlowSynchronizerActor.selfChainTips.isEmpty is true
    blockFlowSynchronizerActor.bestChainTips.isEmpty is true
    blockFlowSynchronizerActor.syncingChains.isEmpty is true

    addBroker(ProtocolV2)
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)
    blockFlowSynchronizerActor.currentVersion is ProtocolV2
    val selfChainTip    = selfChainTips(0)
    val bestChainTip    = selfChainTip.copy(weight = selfChainTip.weight + Weight(1))
    val remoteChainTips = selfChainTips.replace(0, bestChainTip)
    blockFlowSynchronizer ! BlockFlowSynchronizer.UpdateChainState(remoteChainTips)
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)
    blockFlowSynchronizerActor.startTime.isDefined is false
    blockFlowSynchronizerActor.isSyncing is true
  }

  it should "resync if an invalid block is received" in new BlockFlowSynchronizerV2Fixture {
    val chainIndex                            = ChainIndex.unsafe(0, 0)
    val selfChainTips                         = genChainTips
    val (brokerActor0, brokerStatus0, probe0) = addBroker()
    val (brokerActor1, _, probe1)             = addBroker()
    val broker0ChainTips =
      selfChainTips.replace(0, selfChainTips(0).copy(weight = selfChainTips(0).weight + Weight(1)))

    probe0.send(blockFlowSynchronizer, BlockFlowSynchronizer.UpdateChainState(broker0ChainTips))
    blockFlowSynchronizer ! FlowHandler.ChainState(selfChainTips)
    blockFlowSynchronizerActor.isSyncing is true
    blockFlowSynchronizerActor.syncingChains.keys.toSeq is Seq(chainIndex)
    blockFlowSynchronizerActor.syncingChains(chainIndex).originBroker is brokerActor0

    val broker1ChainTips =
      selfChainTips.replace(0, selfChainTips(0).copy(weight = selfChainTips(0).weight + Weight(2)))
    probe1.send(blockFlowSynchronizer, BlockFlowSynchronizer.UpdateChainState(broker1ChainTips))
    val block = emptyBlock(blockFlow, chainIndex)
    blockFlowSynchronizer ! ChainHandler.InvalidFlowData(
      block,
      DataOrigin.InterClique(brokerStatus0.info)
    )
    blockFlowSynchronizerActor.isSyncing is true
    blockFlowSynchronizerActor.syncingChains.keys.toSeq is Seq(chainIndex)
    blockFlowSynchronizerActor.syncingChains(chainIndex).originBroker is brokerActor1
  }

  behavior of "SyncStatePerChain"

  trait SyncStatePerChainFixture extends Fixture {
    import BrokerStatusTracker.BrokerActor
    import SyncState._

    val originBroker: BrokerActor  = ActorRefT(TestProbe().ref)
    val brokerInfo                 = brokerInfoGen.sample.get
    val chainIndex                 = ChainIndex.unsafe(0, 0)
    val invalidBroker: BrokerActor = ActorRefT(TestProbe().ref)

    def newState(bestHeight: Int = MaxQueueSize): SyncStatePerChain = {
      val chainTip = chainTipGen.sample.get.copy(height = bestHeight)
      SyncStatePerChain(chainIndex, chainTip, originBroker)
    }
  }

  it should "get skeleton heights" in new SyncStatePerChainFixture {
    import SyncState._

    val bestHeight0 = BatchSize + 1
    val state0      = newState(bestHeight0)
    state0.initSkeletonHeights(state0.originBroker, 2) is None
    state0.taskQueue.dequeue().id is TaskId(2, bestHeight0)
    state0.nextFromHeight is bestHeight0 + 1
    state0.skeletonHeightRange is None

    state0.initSkeletonHeights(state0.originBroker, 1).get.heights is AVector(bestHeight0 - 1)
    state0.taskQueue.isEmpty is true
    state0.nextFromHeight is bestHeight0
    state0.skeletonHeightRange.get.heights is AVector(bestHeight0 - 1)
    state0.nextSkeletonHeights(state0.nextFromHeight, MaxQueueSize) is None
    state0.taskQueue.dequeue().id is TaskId(bestHeight0, bestHeight0)
    state0.nextFromHeight is bestHeight0 + 1
    state0.skeletonHeightRange is None

    state0.initSkeletonHeights(invalidBroker, 1) is None

    val bestHeight1 = MaxQueueSize
    val state1      = newState(bestHeight1)
    val range       = BlockHeightRange.from(BatchSize, MaxQueueSize, BatchSize)
    state1.initSkeletonHeights(state0.originBroker, 1) is Some(range)
    state1.taskQueue.isEmpty is true
    state1.nextFromHeight is bestHeight1 + 1
    state1.skeletonHeightRange is Some(range)

    1.until(bestHeight1 - BatchSize, BatchSize).zipWithIndex.foreach { case (fromHeight, index) =>
      val expectedRange =
        BlockHeightRange.from(range.from + range.step * index, range.to, range.step)
      state1.nextSkeletonHeights(fromHeight, MaxQueueSize) is Some(expectedRange)
      state1.taskQueue.isEmpty is true
      state1.nextFromHeight is bestHeight1 + 1
      state1.skeletonHeightRange is Some(expectedRange)
    }

    (BatchSize + 1).until(MaxQueueSize, BatchSize).zipWithIndex.foreach { case (size, index) =>
      val expectedRange =
        BlockHeightRange.from(range.from, range.from + range.step * index, range.step)
      state1.nextSkeletonHeights(1, size) is Some(expectedRange)
      state1.taskQueue.isEmpty is true
      state1.nextFromHeight is size
      state1.skeletonHeightRange is Some(expectedRange)
    }

    state1.nextSkeletonHeights(bestHeight1 - BatchSize + 1, MaxQueueSize) is None
    state1.nextFromHeight is bestHeight1 + 1
    state1.skeletonHeightRange.isEmpty is true
    state1.taskQueue.toSeq is Seq(
      BlockDownloadTask(chainIndex, bestHeight1 - BatchSize + 1, bestHeight1, None)
    )
  }

  it should "handle skeleton headers" in new SyncStatePerChainFixture {
    import SyncState._

    val state   = newState()
    val heights = BlockHeightRange.from(50, 200, 50)
    val headers = AVector.fill(4)(emptyBlock(blockFlow, chainIndex).header)
    state.skeletonHeightRange = Some(heights)
    state.taskQueue.isEmpty is true
    state.taskIds.isEmpty is true

    state.onSkeletonFetched(invalidBroker, heights, headers)
    state.skeletonHeightRange is Some(heights)
    state.taskQueue.isEmpty is true
    state.taskIds.isEmpty is true

    state.onSkeletonFetched(state.originBroker, heights, headers)
    state.skeletonHeightRange is None
    val tasks = headers.mapWithIndex { case (toHeader, index) =>
      BlockDownloadTask(chainIndex, 50 * index + 1, 50 * (index + 1), Some(toHeader))
    }
    AVector.from(state.taskQueue) is tasks
    state.taskIds.toSet is Set(TaskId(1, 50), TaskId(51, 100), TaskId(101, 150), TaskId(151, 200))
  }

  it should "get next task" in new SyncStatePerChainFixture {
    import SyncState._

    val state = newState()
    state.isTaskQueueEmpty is true
    val tasks = AVector(
      BlockDownloadTask(chainIndex, 1, 50, None),
      BlockDownloadTask(chainIndex, 51, 100, None)
    )
    state.taskIds.addAll(tasks.map(_.id))
    state.putBack(tasks)
    state.isTaskQueueEmpty is false
    state.taskSize is 2

    state.nextTask(_ => false)
    state.taskSize is 2

    var newTask: Option[BlockDownloadTask] = None
    val handler = (task: BlockDownloadTask) => { newTask = Some(task); true }
    state.nextTask(handler)
    newTask is Some(tasks(0))
    state.taskSize is 1

    state.nextTask(handler)
    newTask is Some(tasks(1))
    state.taskSize is 0
    state.isTaskQueueEmpty is true

    state.nextTask(handler)
    newTask is Some(tasks(1))
  }

  it should "handle downloaded blocks" in new SyncStatePerChainFixture {
    import SyncState._

    val state      = newState()
    val taskId0    = TaskId(1, 4)
    val taskId1    = TaskId(5, 8)
    val blocks0    = AVector.fill(4)(emptyBlock(blockFlow, chainIndex))
    val blocks1    = AVector.fill(4)(emptyBlock(blockFlow, chainIndex))
    val fromBroker = (state.originBroker, brokerInfo)
    state.taskIds.isEmpty is true

    state.downloadedBlocks.isEmpty is true
    state.onBlockDownloaded(state.originBroker, brokerInfo, taskId0, blocks0)
    state.downloadedBlocks.isEmpty is true

    state.taskIds.addAll(Seq(taskId0, taskId1))
    state.onBlockDownloaded(state.originBroker, brokerInfo, taskId1, blocks1)
    state.taskIds.size is 2
    state.downloadedBlocks.size is 1
    state.blockQueue.isEmpty is true

    state.onBlockDownloaded(state.originBroker, brokerInfo, taskId0, blocks0)
    state.taskIds.isEmpty is true
    state.downloadedBlocks.isEmpty is true
    val downloadedBlocks = (blocks0 ++ blocks1).map(b => (b.hash, DownloadedBlock(b, fromBroker)))
    state.blockQueue.toSeq is Seq.from(downloadedBlocks)
  }

  it should "put back tasks to the queue" in new SyncStatePerChainFixture {
    import SyncState._

    val state = newState()
    val tasks = AVector(
      BlockDownloadTask(chainIndex, 51, 100, None),
      BlockDownloadTask(chainIndex, 101, 150, None),
      BlockDownloadTask(chainIndex, 1, 50, None)
    )
    state.taskIds.add(tasks(0).id)
    state.taskIds.add(tasks(2).id)

    state.putBack(tasks)
    AVector.from(state.taskQueue) is AVector(tasks(2), tasks(0))
  }

  it should "try to validate more blocks" in new SyncStatePerChainFixture with BlockGenerators {
    import SyncState._

    val state = newState()
    val acc   = mutable.ArrayBuffer.empty[DownloadedBlock]

    state.downloadedBlocks.isEmpty is true
    state.blockQueue.isEmpty is true
    state.tryValidateMoreBlocks(acc)
    acc.isEmpty is true

    val taskId0           = TaskId(51, 100)
    val taskId1           = TaskId(101, 140)
    val blocks0           = AVector.fill(50)(blockGen(chainIndex).sample.get)
    val blocks1           = AVector.fill(40)(blockGen(chainIndex).sample.get)
    val fromBroker        = (state.originBroker, brokerInfo)
    val downloadedBlocks0 = blocks0.map(b => DownloadedBlock(b, fromBroker))
    val downloadedBlocks1 = blocks1.map(b => DownloadedBlock(b, fromBroker))
    state.taskIds.addAll(Seq(taskId0, taskId1))

    state.onBlockDownloaded(state.originBroker, brokerInfo, taskId0, blocks0)
    state.blockQueue.size is blocks0.length
    state.tryValidateMoreBlocks(acc)
    acc.toSeq is Seq.from(downloadedBlocks0)
    state.validating.size is acc.length
    state.blockQueue.isEmpty is true

    state.onBlockDownloaded(state.originBroker, brokerInfo, taskId1, blocks1)
    state.blockQueue.size is blocks1.length
    state.tryValidateMoreBlocks(acc)
    acc.toSeq is Seq.from(downloadedBlocks0)
    state.validating.size is acc.length
    state.blockQueue.size is blocks1.length

    blocks0.foreach(b => state.handleFinalizedBlock(b.hash))
    state.tryValidateMoreBlocks(acc)
    acc.toSeq is Seq.from(downloadedBlocks0 ++ downloadedBlocks1)
    state.validating.size is blocks1.length
    state.blockQueue.isEmpty is true
  }

  it should "remove finalized blocks" in new SyncStatePerChainFixture {
    import SyncState._

    val state            = newState()
    val taskId           = TaskId(51, 100)
    val blocks           = AVector.fill(2)(emptyBlock(blockFlow, chainIndex))
    val fromBroker       = (state.originBroker, brokerInfo)
    val downloadedBlocks = blocks.map(b => DownloadedBlock(b, fromBroker))
    state.taskIds.addOne(taskId)

    state.onBlockDownloaded(state.originBroker, brokerInfo, taskId, blocks)
    state.blockQueue.size is downloadedBlocks.length

    state.tryValidateMoreBlocks(mutable.ArrayBuffer.empty)
    state.validating.size is downloadedBlocks.length
    state.blockQueue.isEmpty is true

    blocks.foreach { block =>
      state.validating.contains(block.hash) is true
      state.blockQueue.contains(block.hash) is false
      state.handleFinalizedBlock(block.hash)
      state.validating.contains(block.hash) is false
      state.blockQueue.contains(block.hash) is false
    }

    state.validating.isEmpty is true
    state.blockQueue.isEmpty is true
  }

  it should "try move on" in new SyncStatePerChainFixture with BlockGenerators {
    import SyncState._

    val state = newState(100)
    state.tryMoveOn() is None

    def reset(): Unit = {
      state.nextFromHeight = 1
      state.skeletonHeightRange = None
      state.taskQueue.clear()
      state.validating.clear()
      state.blockQueue.clear()
    }

    def testMoveOne(range: Option[BlockHeightRange]) = {
      range match {
        case None => state.tryMoveOn() is None
        case Some(range) =>
          state.tryMoveOn() is Some(range)
          state.nextFromHeight is (range.heights.last + 1)
          state.skeletonHeightRange is Some(range)
      }
    }

    reset()
    state.nextFromHeight = state.bestTip.height + 1
    testMoveOne(None)

    reset()
    state.skeletonHeightRange = Some(BlockHeightRange.fromHeight(50))
    testMoveOne(None)

    reset()
    state.taskQueue.addOne(BlockDownloadTask(chainIndex, 1, 50, None))
    testMoveOne(None)

    reset()
    val blocks = Seq.fill(MaxQueueSize / 2 + 1)(blockGen(chainIndex).sample.get)
    blocks.foreach(b => state.validating.add(b.hash))
    testMoveOne(None)
    state.validating.remove(state.validating.head)
    testMoveOne(Some(BlockHeightRange.from(50, 100, 50)))

    val fromBroker = (state.originBroker, brokerInfo)
    reset()
    blocks.foreach(b => state.blockQueue.addOne(b.hash -> DownloadedBlock(b, fromBroker)))
    testMoveOne(None)
    state.blockQueue.remove(state.blockQueue.head._1)
    testMoveOne(Some(BlockHeightRange.from(50, 100, 50)))

    reset()
    blocks.view.take(blocks.length / 2 + 1).foreach { b =>
      state.validating.addOne(b.hash)
      state.blockQueue.addOne(b.hash -> DownloadedBlock(b, fromBroker))
    }
    testMoveOne(None)
    state.blockQueue.remove(blocks.head.hash)
    state.validating.remove(blocks.head.hash)
    testMoveOne(Some(BlockHeightRange.from(50, 100, 50)))

    reset()
    val taskId = TaskId(50, 100)
    state.taskIds.addOne(taskId)
    testMoveOne(None)
    state.downloadedBlocks.addOne((taskId, AVector.empty))
    testMoveOne(Some(BlockHeightRange.from(50, 100, 50)))
  }
}
