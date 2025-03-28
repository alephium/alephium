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
import scala.util.Random

import org.alephium.flow.core.maxSyncBlocksPerChain
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.network.sync.SyncState.{BlockBatch, BlockDownloadTask}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.{P2PV1, P2PVersion}
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector}

object BrokerStatusTracker {
  type BrokerActor = ActorRefT[BrokerHandler.Command]

  val MaxRequestNum: Int = maxSyncBlocksPerChain * 16

  final class BrokerStatus(
      val info: BrokerInfo,
      val version: P2PVersion,
      private[sync] val tips: FlattenIndexedArray[ChainTip]
  ) {
    private[sync] var requestNum   = 0
    private[sync] val pendingTasks = mutable.Set.empty[BlockDownloadTask]
    private[sync] val missedBlocks = mutable.HashMap.empty[ChainIndex, mutable.Set[BlockBatch]]

    def updateTips(newTips: AVector[ChainTip])(implicit groupConfig: GroupConfig): Unit =
      newTips.foreach(tip => tips(tip.chainIndex) = tip)

    def getChainTip(chainIndex: ChainIndex)(implicit groupConfig: GroupConfig): Option[ChainTip] =
      tips(chainIndex)

    def canDownload(task: BlockDownloadTask)(implicit groupConfig: GroupConfig): Boolean = {
      requestNum < MaxRequestNum &&
      !pendingTasks.contains(task) &&
      !missOrUnableDownload(task.chainIndex, task.id)
    }
    def addPendingTask(task: BlockDownloadTask): Unit = {
      requestNum += task.size
      pendingTasks.addOne(task)
    }
    def removePendingTask(task: BlockDownloadTask): Unit = {
      if (pendingTasks.remove(task)) {
        requestNum -= task.size
      }
    }
    def recycleTasks(
        chains: FlattenIndexedArray[SyncState.SyncStatePerChain]
    )(implicit groupConfig: GroupConfig): Int = {
      if (pendingTasks.nonEmpty) {
        var count = 0
        pendingTasks.foreach { task =>
          if (chains(task.chainIndex).exists(_.putBack(task))) {
            count += 1
          }
        }
        count
      } else {
        0
      }
    }

    def handleBlockDownloaded(
        result: AVector[(BlockDownloadTask, AVector[Block], Boolean)]
    ): Unit = {
      result.foreach { case (task, _, isValid) =>
        removePendingTask(task)
        if (!isValid) addMissedBlocks(task.chainIndex, task.id)
      }
    }

    def addMissedBlocks(chainIndex: ChainIndex, batchId: BlockBatch): Unit = {
      missedBlocks.get(chainIndex) match {
        case Some(values) => values.addOne(batchId)
        case None         => missedBlocks(chainIndex) = mutable.Set(batchId)
      }
    }
    def clearMissedBlocks(chainIndex: ChainIndex): Unit = {
      missedBlocks.remove(chainIndex)
      ()
    }
    @inline def containsMissedBlocks(
        chainIndex: ChainIndex,
        batchId: BlockBatch
    ): Boolean = {
      missedBlocks.get(chainIndex).exists(_.contains(batchId))
    }
    def missOrUnableDownload(
        chainIndex: ChainIndex,
        batchId: BlockBatch
    )(implicit groupConfig: GroupConfig): Boolean = {
      containsMissedBlocks(chainIndex, batchId) || {
        val chainTip = getChainTip(chainIndex)
        chainTip.forall(_.height < batchId.to)
      }
    }

    def clear(): Unit = {
      requestNum = 0
      pendingTasks.clear()
      missedBlocks.clear()
    }
  }

  object BrokerStatus {
    def apply(info: BrokerInfo, version: P2PVersion)(implicit
        groupConfig: GroupConfig
    ): BrokerStatus = {
      new BrokerStatus(info, version, FlattenIndexedArray.empty)
    }
  }
}

trait BrokerStatusTracker {
  import BrokerStatusTracker._
  def networkSetting: NetworkSetting

  val brokers: mutable.ArrayBuffer[(BrokerActor, BrokerStatus)] =
    mutable.ArrayBuffer.empty

  def getBrokerStatus(broker: BrokerActor): Option[BrokerStatus] =
    brokers.find(_._1 == broker).map(_._2)

  def samplePeersSize(brokerSize: Int, p2pVersion: P2PVersion): Int = {
    val syncPeerSampleSize = if (p2pVersion == P2PV1) {
      networkSetting.syncPeerSampleSizeV1
    } else {
      networkSetting.syncPeerSampleSizeV2
    }
    val peerSize = Math.sqrt(brokerSize.toDouble).toInt
    Math.min(peerSize, syncPeerSampleSize)
  }

  private def samplePeers(
      filtered: scala.collection.Seq[(BrokerActor, BrokerStatus)],
      version: P2PVersion
  ): AVector[(BrokerActor, BrokerStatus)] = {
    if (filtered.isEmpty) {
      AVector.empty
    } else {
      val peerSize   = samplePeersSize(filtered.size, version)
      val startIndex = Random.nextInt(filtered.size)
      AVector.tabulate(peerSize) { k =>
        filtered((startIndex + k) % filtered.size)
      }
    }
  }

  def sampleV1PeersFromAllBrokers(): AVector[(BrokerActor, BrokerStatus)] = {
    samplePeers(brokers, P2PV1)
  }

  def samplePeers(version: P2PVersion): AVector[(BrokerActor, BrokerStatus)] = {
    val filtered = brokers.filter(_._2.version == version)
    samplePeers(filtered, version)
  }
}
