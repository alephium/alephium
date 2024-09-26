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

import akka.testkit.{TestActorRef, TestProbe}

import org.alephium.flow.FlowFixture
import org.alephium.flow.handler.TestUtils
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.network.broker.{BrokerHandler, InboundConnection}
import org.alephium.protocol.Generators
import org.alephium.protocol.message.ProtocolV1
import org.alephium.protocol.model.{BlockGenerators, BlockHash, ChainIndex}
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector}

class BlockFlowSynchronizerSpec extends AlephiumActorSpec {
  trait Fixture extends FlowFixture with Generators {
    val (allHandlers, _) = TestUtils.createAllHandlersProbe
    val blockFlowSynchronizer = TestActorRef[BlockFlowSynchronizer](
      BlockFlowSynchronizer.props(blockFlow, allHandlers)
    )
    val blockFlowSynchronizerActor = blockFlowSynchronizer.underlyingActor
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
    state0.skeletonHeights is None

    state0.initSkeletonHeights(state0.originBroker, 1) is Some(AVector(bestHeight0 - 1))
    state0.taskQueue.isEmpty is true
    state0.nextFromHeight is bestHeight0
    state0.skeletonHeights is Some(AVector(bestHeight0 - 1))
    state0.nextSkeletonHeights(state0.nextFromHeight, MaxQueueSize) is None
    state0.taskQueue.dequeue().id is TaskId(bestHeight0, bestHeight0)
    state0.nextFromHeight is bestHeight0 + 1
    state0.skeletonHeights is None

    state0.initSkeletonHeights(invalidBroker, 1) is None

    val bestHeight1     = MaxQueueSize
    val state1          = newState(bestHeight1)
    val skeletonHeights = AVector.from(BatchSize.to(MaxQueueSize, BatchSize))
    state1.initSkeletonHeights(state0.originBroker, 1) is Some(skeletonHeights)
    state1.taskQueue.isEmpty is true
    state1.nextFromHeight is bestHeight1 + 1
    state1.skeletonHeights is Some(skeletonHeights)
  }

  it should "handle skeleton headers" in new SyncStatePerChainFixture {
    import SyncState._

    val state   = newState()
    val heights = AVector.from(50.to(200, 50))
    val headers = AVector.fill(4)(emptyBlock(blockFlow, chainIndex).header)
    state.skeletonHeights = Some(heights)
    state.taskQueue.isEmpty is true
    state.taskIds.isEmpty is true

    state.onSkeletonFetched(invalidBroker, heights, headers)
    state.skeletonHeights is Some(heights)
    state.taskQueue.isEmpty is true
    state.taskIds.isEmpty is true

    state.onSkeletonFetched(state.originBroker, heights, headers)
    state.skeletonHeights is None
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
    state.tryValidateMoreBlocks(acc)
    acc.toSeq is Seq.from(downloadedBlocks0)
    state.validating.size is acc.length

    state.onBlockDownloaded(state.originBroker, brokerInfo, taskId1, blocks1)
    state.tryValidateMoreBlocks(acc)
    acc.toSeq is Seq.from(downloadedBlocks0)
    state.validating.size is acc.length

    blocks0.foreach(b => state.handleFinalizedBlock(b.hash))
    state.tryValidateMoreBlocks(acc)
    acc.toSeq is Seq.from(downloadedBlocks0 ++ downloadedBlocks1)
    state.validating.size is blocks1.length
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
    state.blockQueue.size is downloadedBlocks.length

    blocks.foreach { block =>
      state.validating.contains(block.hash) is true
      state.blockQueue.contains(block.hash) is true
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

    state.nextFromHeight = state.bestTip.height + 1
    state.tryMoveOn() is None
    state.nextFromHeight = 1

    state.skeletonHeights = Some(AVector(50))
    state.tryMoveOn() is None
    state.skeletonHeights = None

    state.taskQueue.addOne(BlockDownloadTask(chainIndex, 1, 50, None))
    state.tryMoveOn() is None
    state.nextTask(_ => true)

    val fromBroker = (state.originBroker, brokerInfo)
    (0 to MaxQueueSize / 2).foreach { _ =>
      val block = blockGen(chainIndex).sample.get
      state.blockQueue.addOne((block.hash, DownloadedBlock(block, fromBroker)))
    }
    state.tryMoveOn() is None
    state.blockQueue.remove(state.blockQueue.head._1)
    state.tryMoveOn() is Some(AVector(50, 100))
    state.nextFromHeight is 101
    state.skeletonHeights is Some(AVector(50, 100))

    state.nextFromHeight = 1
    state.skeletonHeights = None
    val taskId = TaskId(50, 100)
    state.taskIds.addOne(taskId)
    state.tryMoveOn() is None
    state.downloadedBlocks.addOne((taskId, AVector.empty))
    state.tryMoveOn() is Some(AVector(50, 100))
    state.nextFromHeight is 101
    state.skeletonHeights is Some(AVector(50, 100))
  }
}
