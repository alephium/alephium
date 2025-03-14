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

package org.alephium.tools

import scala.collection.mutable.PriorityQueue

import org.alephium.flow.core.BlockFlow
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Block, ChainIndex, GroupIndex}
import org.alephium.protocol.vm.WorldState
import org.alephium.util.{AVector, TimeStamp}

trait ReplayState {
  def sourceBlockFlow: BlockFlow
  def targetBlockFlow: BlockFlow

  private val brokerConfig: BrokerConfig        = sourceBlockFlow.brokerConfig
  private val chainIndexes: AVector[ChainIndex] = brokerConfig.chainIndexes

  private val startLoadingHeight        = 1
  private val loadedHeights: Array[Int] = chainIndexes.map(_ => startLoadingHeight).toArray
  private val maxHeights: Array[Int]    = Array.fill(chainIndexes.length)(0)

  protected val blockQueue =
    PriorityQueue.empty(Ordering.by[(Block, Int), TimeStamp](t => t._1.timestamp).reverse)
  protected var replayedBlockCount: Long = 0
  protected var loadedBlockCount: Long   = 0

  private val startTs = TimeStamp.now()
  private var countTs = TimeStamp.now()

  final protected def init(): IOResult[Unit] = {
    chainIndexes
      .foreachE { chainIndex =>
        val chainIndexOneDim = chainIndex.flattenIndex(brokerConfig)
        for {
          maxHeight <- sourceBlockFlow.getMaxHeightByWeight(chainIndex)
          height0   <- targetBlockFlow.getMaxHeightByWeight(chainIndex)
          height = if (height0 > startLoadingHeight) height0 + 1 else startLoadingHeight
          _ <- IOUtils.tryExecute(loadBlocksAtUnsafe(chainIndex, height))
        } yield {
          maxHeights(chainIndexOneDim) = maxHeight
          loadedHeights(chainIndexOneDim) = height
        }
      }
      .map { _ =>
        replayedBlockCount = loadedHeights.map(_ - 1).sum.toLong
        loadedBlockCount = replayedBlockCount
      }
  }

  private def loadBlocksAtUnsafe(chainIndex: ChainIndex, height: Int): Unit = {
    val hashes = sourceBlockFlow.getBlockChain(chainIndex).getHashesUnsafe(height)
    require(
      hashes.toArray.distinct.length == hashes.length,
      s"Hashes from ${chainIndex} at height ${height} are not unique: ${hashes}"
    )
    hashes.foreach(blockHash =>
      blockQueue.enqueue(sourceBlockFlow.getBlockUnsafe(blockHash) -> height)
    )
  }

  final protected def loadMoreBlocksUnsafe(chainIndex: ChainIndex, blockHeight: Int): Unit = {
    val chainIndexOneDim = chainIndex.flattenIndex(brokerConfig)
    val shouldLoadMore =
      loadedHeights(chainIndexOneDim) == blockHeight &&
        blockHeight < maxHeights(chainIndexOneDim)

    if (shouldLoadMore) {
      loadBlocksAtUnsafe(chainIndex, blockHeight + 1)
      loadedHeights(chainIndexOneDim) = blockHeight + 1
    }
  }

  private def fetchBestWorldStateHashes(
      blockFlow: BlockFlow
  ): IOResult[AVector[WorldState.Hashes]] = {
    blockFlow.brokerConfig.cliqueGroupIndexes.mapE { groupIndex =>
      blockFlow.getBestPersistedWorldState(groupIndex).map(_.toHashes)
    }
  }

  private def updateTargetBlockFlowBestDeps(): IOResult[Unit] = {
    IOUtils.tryExecute(brokerConfig.groupRange.foreach { mainGroup =>
      val deps = targetBlockFlow.calBestDepsUnsafe(GroupIndex.unsafe(mainGroup)(brokerConfig))
      targetBlockFlow.updateBestDepsPreDanube(mainGroup, deps)
    })
  }

  final protected def isStateHashesSame: IOResult[Boolean] = {
    for {
      _            <- updateTargetBlockFlowBestDeps()
      sourceHashes <- fetchBestWorldStateHashes(sourceBlockFlow)
      targetHashes <- fetchBestWorldStateHashes(targetBlockFlow)
    } yield sourceHashes == targetHashes
  }

  @inline final protected def calcSpeed(): (Long, Long) = {
    val now           = TimeStamp.now()
    val eclipsed      = now.deltaUnsafe(startTs).millis
    val speed         = (replayedBlockCount - loadedBlockCount) * 1000L / eclipsed
    val cycleEclipsed = now.deltaUnsafe(countTs).millis
    val cycleSpeed    = ReplayState.LogInterval * 1000 / cycleEclipsed
    countTs = now
    (speed, cycleSpeed)
  }
}

object ReplayState {
  val LogInterval: Int = 1000
}
