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
import org.alephium.flow.network.sync.SyncState.{BlockDownloadTask, TaskId}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{ProtocolV1, ProtocolV2, ProtocolVersion}
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector}

object BrokerStatusTracker {
  type BrokerActor = ActorRefT[BrokerHandler.Command]

  val MaxRequestNum: Int = maxSyncBlocksPerChain * 16

  final class BrokerStatus(val info: BrokerInfo, val version: ProtocolVersion) {
    private[sync] var tips: Option[AVector[ChainTip]] = None
    private[sync] var requestNum                      = 0
    private[sync] val pendingTasks                    = mutable.Set.empty[BlockDownloadTask]
    private[sync] val missedBlocks = mutable.HashMap.empty[ChainIndex, mutable.Set[TaskId]]

    def contains(chainIndex: ChainIndex): Boolean = info.contains(chainIndex.from)

    def updateTips(newTips: AVector[ChainTip]): Unit = tips = Some(newTips)

    def getChainTip(
        chainIndex: ChainIndex
    )(implicit brokerConfig: BrokerConfig): Option[ChainTip] = {
      if (contains(chainIndex)) {
        tips.flatMap(_.find(_.chainIndex == chainIndex))
      } else {
        None
      }
    }

    def canDownload(task: BlockDownloadTask)(implicit brokerConfig: BrokerConfig): Boolean = {
      requestNum < MaxRequestNum &&
      !containsMissedBlocks(task.chainIndex, task.id) &&
      getChainTip(task.chainIndex).exists(_.height >= task.to)
    }
    def getRequestNum: Int = requestNum
    def addPendingTask(task: BlockDownloadTask): Unit = {
      requestNum += task.size
      pendingTasks.addOne(task)
    }
    def removePendingTask(task: BlockDownloadTask): Unit = {
      if (pendingTasks.remove(task)) {
        requestNum -= task.size
      }
    }
    def getPendingTasks: collection.Set[BlockDownloadTask] = pendingTasks

    def addMissedBlocks(chainIndex: ChainIndex, taskId: TaskId): Unit = {
      missedBlocks.get(chainIndex) match {
        case Some(values) => values.addOne(taskId)
        case None         => missedBlocks(chainIndex) = mutable.Set(taskId)
      }
    }
    def clearMissedBlocks(chainIndex: ChainIndex): Unit = {
      missedBlocks.remove(chainIndex)
      ()
    }
    def containsMissedBlocks(chainIndex: ChainIndex, taskId: TaskId): Boolean = {
      missedBlocks.get(chainIndex).exists(_.contains(taskId))
    }

    def clear(): Unit = {
      requestNum = 0
      pendingTasks.clear()
      missedBlocks.clear()
    }
  }

  object BrokerStatus {
    def apply(info: BrokerInfo, version: ProtocolVersion): BrokerStatus =
      new BrokerStatus(info, version)
  }
}

trait BrokerStatusTracker {
  import BrokerStatusTracker._
  def networkSetting: NetworkSetting

  val brokers: mutable.ArrayBuffer[(BrokerActor, BrokerStatus)] =
    mutable.ArrayBuffer.empty

  def getBrokerStatus(broker: BrokerActor): Option[BrokerStatus] =
    brokers.find(_._1 == broker).map(_._2)

  def samplePeersSize(brokerSize: Int): Int = {
    val peerSize = Math.sqrt(brokerSize.toDouble).toInt
    Math.min(peerSize, networkSetting.syncPeerSampleSize)
  }

  def samplePeers(version: ProtocolVersion): AVector[(BrokerActor, BrokerStatus)] = {
    val filtered = version match {
      case ProtocolV2 => brokers.filter(_._2.version == ProtocolV2)
      case ProtocolV1 => brokers
    }
    if (filtered.isEmpty) {
      AVector.empty
    } else {
      val peerSize   = samplePeersSize(filtered.size)
      val startIndex = Random.nextInt(filtered.size)
      AVector.tabulate(peerSize) { k =>
        filtered((startIndex + k) % filtered.size)
      }
    }
  }
}
