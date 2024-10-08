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

import akka.actor.ActorRef
import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.network.sync.BrokerStatusTracker.BrokerStatus
import org.alephium.flow.network.sync.SyncState.{BlockDownloadTask, TaskId}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.Generators
import org.alephium.protocol.message.{ProtocolV1, ProtocolV2, ProtocolVersion}
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector}

class BrokerStatusTrackerSpec extends AlephiumFlowActorSpec with Generators {
  val brokerInfo = brokerInfoGen.sample.get

  trait Fixture extends BrokerStatusTracker {
    def networkSetting: NetworkSetting = config.network

    def addNewBroker(version: ProtocolVersion): ActorRef = {
      val broker = TestProbe().ref
      brokers += ActorRefT(broker) -> BrokerStatus(brokerInfo, version)
      broker
    }
  }

  it should "sample the right size" in new Fixture {
    networkSetting.syncPeerSampleSize is 3

    (1 until 4).foreach(_ => addNewBroker(ProtocolV1))
    samplePeersSize(brokers.size) is 1
    samplePeers(ProtocolV1).toSeq.toMap.size is 1
    samplePeers(ProtocolV2).isEmpty is true
    (4 until 9).foreach(_ => addNewBroker(ProtocolV2))
    samplePeersSize(brokers.size) is 2
    samplePeers(ProtocolV1).toSeq.toMap.size is 2
    (9 until 1024).foreach(_ => addNewBroker(ProtocolV1))
    samplePeersSize(brokers.size) is 3
    samplePeers(ProtocolV1).toSeq.toMap.size is 3

    samplePeers(ProtocolV2).toSeq.toMap.size is 2
    (1 until 4).foreach(_ => addNewBroker(ProtocolV2))
    samplePeers(ProtocolV2).toSeq.toMap.size is 2
    (9 until 1024).foreach(_ => addNewBroker(ProtocolV2))
    samplePeers(ProtocolV2).toSeq.toMap.size is 3
  }

  behavior of "BrokerStatus"

  trait BrokerStatusFixture {
    val info   = BrokerInfo.unsafe(brokerInfo.cliqueId, 0, brokerConfig.groups, brokerInfo.address)
    val status = BrokerStatus(info, ProtocolV2)
    val groupRange = AVector.from(brokerConfig.calIntersection(status.info))
    groupRange is AVector(0)

    val chainIndexes = groupRange.flatMap(from =>
      AVector.tabulate(brokerConfig.groups)(to => ChainIndex.unsafe(from, to))
    )

    def genChainTips(height: Int = 0, weight: Weight = Weight.zero) = {
      chainIndexes.map { chainIndex =>
        val block = emptyBlock(blockFlow, chainIndex)
        ChainTip(block.hash, height, weight)
      }
    }
  }

  it should "getChainTip" in new BrokerStatusFixture {
    status.tips.isEmpty is true
    chainIndexes.foreach(status.getChainTip(_) is None)

    val chainTips = genChainTips()
    status.updateTips(chainTips)
    status.tips is Some(chainTips)

    chainIndexes.foreachWithIndex { case (chainIndex, index) =>
      status.getChainTip(chainIndex) is Some(chainTips(index))
    }
    status.info.contains(GroupIndex.unsafe(1)) is false
    AVector.tabulate(brokerConfig.groups)(to => ChainIndex.unsafe(1, to)).foreach { chainIndex =>
      status.getChainTip(chainIndex) is None
    }
  }

  it should "canDownload" in new BrokerStatusFixture {
    val task = BlockDownloadTask(ChainIndex.unsafe(0, 0), 1, 5, None)
    status.canDownload(task) is false

    status.updateTips(genChainTips(5))
    status.requestNum = BrokerStatusTracker.MaxRequestNum
    status.canDownload(task) is false
    status.requestNum = 0

    status.canDownload(task) is true
    status.addMissedBlocks(task.chainIndex, task.id)
    status.canDownload(task) is false
    status.clearMissedBlocks(task.chainIndex)

    status.canDownload(task) is true
    status.canDownload(task.copy(to = 6)) is false
    status.canDownload(task.copy(chainIndex = ChainIndex.unsafe(1, 1))) is false
  }

  it should "add/get/remove pending requests" in new BrokerStatusFixture {
    val task0 = BlockDownloadTask(ChainIndex.unsafe(0, 0), 1, 5, None)
    val task1 = task0.copy(from = 6, to = 10)
    val task2 = task0.copy(from = 11, to = 15)

    status.pendingTasks.contains(task0) is false
    status.pendingTasks.contains(task1) is false
    status.pendingTasks.contains(task2) is false
    status.addPendingTask(task0)
    status.requestNum is 5
    status.addPendingTask(task1)
    status.requestNum is 10

    status.pendingTasks.contains(task0) is true
    status.pendingTasks.contains(task1) is true
    status.pendingTasks.contains(task2) is false
    status.getPendingTasks.toSet is Set(task0, task1)

    status.removePendingTask(task0)
    status.requestNum is 5
    status.pendingTasks.contains(task0) is false
    status.pendingTasks.contains(task1) is true

    status.removePendingTask(task2)
    status.requestNum is 5
    status.pendingTasks.contains(task0) is false
    status.pendingTasks.contains(task1) is true

    status.removePendingTask(task1)
    status.pendingTasks.contains(task0) is false
    status.pendingTasks.contains(task1) is false
    status.pendingTasks.contains(task2) is false
  }

  it should "add/contains/clear missed blocks" in new BrokerStatusFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val taskId0    = TaskId(1, 5)
    val taskId1    = TaskId(6, 10)
    status.containsMissedBlocks(chainIndex, taskId0) is false
    status.containsMissedBlocks(chainIndex, taskId1) is false

    status.addMissedBlocks(chainIndex, taskId0)
    status.addMissedBlocks(chainIndex, taskId1)

    status.containsMissedBlocks(chainIndex, taskId0) is true
    status.containsMissedBlocks(chainIndex, taskId1) is true

    status.clearMissedBlocks(chainIndex)
    status.containsMissedBlocks(chainIndex, taskId0) is false
    status.containsMissedBlocks(chainIndex, taskId1) is false
  }

  it should "clear state" in new BrokerStatusFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    status.addPendingTask(BlockDownloadTask(chainIndex, 1, 5, None))
    status.pendingTasks.nonEmpty is true
    status.requestNum is 5
    status.addMissedBlocks(chainIndex, TaskId(1, 5))
    status.missedBlocks.nonEmpty is true

    status.clear()
    status.pendingTasks.isEmpty is true
    status.requestNum is 0
    status.missedBlocks.isEmpty is true
  }
}
