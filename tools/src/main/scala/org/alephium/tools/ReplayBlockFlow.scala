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

import java.nio.file.{Files, StandardCopyOption}

import scala.collection.mutable.PriorityQueue

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.Node
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.setting.Platform
import org.alephium.flow.validation.{BlockValidation, BlockValidationResult}
import org.alephium.io.IOResult
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.protocol.vm.WorldState
import org.alephium.util.{AVector, Files => AFiles, TimeStamp}

class ReplayBlockFlow(
    sourceBlockFlow: BlockFlow,
    targetBlockFlow: BlockFlow
) extends StrictLogging {
  private val startLoadingHeight = 1
  private val brokerConfig       = sourceBlockFlow.brokerConfig
  private val chainIndexes       = brokerConfig.chainIndexes
  private val validator          = BlockValidation.build(targetBlockFlow)
  private val loadedHeights      = chainIndexes.map(_ => startLoadingHeight).toArray
  private val pendingBlocks =
    PriorityQueue.empty(Ordering.by[(Block, Int), TimeStamp](t => t._1.timestamp).reverse)

  def start(): BlockValidationResult[Boolean] = {
    for {
      maxHeights <- from(
        chainIndexes.mapE(chainIndex => sourceBlockFlow.getMaxHeightByWeight(chainIndex))
      )
      _                 <- from(loadInitialBlocks(targetBlockFlow))
      _                 <- replay(maxHeights)
      sourceStateHashes <- fetchBestWorldStateHashes(sourceBlockFlow)
      targetStateHashes <- fetchBestWorldStateHashes(targetBlockFlow)
    } yield sourceStateHashes == targetStateHashes
  }

  private def replay(maxHeights: AVector[Int]): BlockValidationResult[Unit] = {
    val startTs = TimeStamp.now()
    var count   = loadedHeights.map(_ - 1).sum
    var countTs = TimeStamp.now()
    var result  = Right(()): BlockValidationResult[Unit]

    while (pendingBlocks.nonEmpty && result.isRight) {
      val (block, blockHeight) = pendingBlocks.dequeue()
      val chainIndex           = block.chainIndex

      var endValidationTs = TimeStamp.zero
      result = for {
        sideEffect <- validator.validate(block, targetBlockFlow)
        _ <- from(targetBlockFlow.add(block, sideEffect)).map(_ =>
          endValidationTs = TimeStamp.now()
        )
        _ <- loadMoreBlocks(chainIndex, maxHeights, blockHeight)
      } yield ()

      count += 1
      if (count % 1000 == 0) {
        val now           = TimeStamp.now()
        val eclipsed      = now.deltaUnsafe(startTs).millis
        val speed         = count * 1000 / eclipsed
        val cycleEclipsed = now.deltaUnsafe(countTs).millis
        val cycleSpeed    = 1000 * 1000 / cycleEclipsed
        countTs = now
        logger.info(s"Replayed #$count blocks, #$speed BPS, #$cycleSpeed cycle BPS")
      }
    }

    result
  }

  private def loadMoreBlocks(
      chainIndex: ChainIndex,
      maxHeights: AVector[Int],
      blockHeight: Int
  ): BlockValidationResult[Unit] = {
    val chainIndexOneDim = chainIndex.flattenIndex(brokerConfig)
    val shouldLoadMore =
      loadedHeights(chainIndexOneDim) == blockHeight && blockHeight < maxHeights(
        chainIndexOneDim
      )

    if (shouldLoadMore) {
      from(
        loadBlocksAt(chainIndex, blockHeight + 1).map(_ =>
          loadedHeights(chainIndexOneDim) = blockHeight + 1
        )
      )
    } else {
      Right(())
    }
  }

  private def loadBlocksAt(chainIndex: ChainIndex, height: Int): IOResult[Unit] = {
    sourceBlockFlow.getHashes(chainIndex, height).map { hashes =>
      require(
        hashes.toArray.distinct.length == hashes.length,
        s"Hashes from ${chainIndex} at height ${height} are not unique: ${hashes}"
      )
      hashes.foreach(blockHash =>
        pendingBlocks.enqueue(sourceBlockFlow.getBlockUnsafe(blockHash) -> height)
      )
    }
  }

  private def fetchBestWorldStateHashes(
      blockFlow: BlockFlow
  ): BlockValidationResult[AVector[WorldState.Hashes]] = {
    from(
      blockFlow.brokerConfig.cliqueGroupIndexes.mapE { groupIndex =>
        blockFlow.getBestPersistedWorldState(groupIndex).map(_.toHashes)
      }
    )
  }

  private def loadInitialBlocks(targetBlockFlow: BlockFlow): IOResult[Unit] = {
    chainIndexes.foreachE { chainIndex =>
      val chainIndexOneDim = chainIndex.flattenIndex(brokerConfig)
      for {
        height0 <- targetBlockFlow.getMaxHeightByWeight(chainIndex)
        height = if (height0 > startLoadingHeight) height0 + 1 else startLoadingHeight
        _ <- loadBlocksAt(chainIndex, height)
      } yield {
        loadedHeights(chainIndexOneDim) = height
      }
    }
  }

  private def from[T](result: IOResult[T]): BlockValidationResult[T] = {
    result.left.map(Left(_))
  }
}

object ReplayBlockFlow extends App with StrictLogging {
  private val sourcePath = Platform.getRootPath()
  private val targetPath = {
    val path = AFiles.homeDir.resolve(".alephium-replay")
    path.toFile.mkdir()
    Files.copy(
      sourcePath.resolve("user.conf"),
      path.resolve("user.conf"),
      StandardCopyOption.REPLACE_EXISTING
    )
    path
  }

  private val (sourceBlockFlow, sourceStorages) = Node.buildBlockFlowUnsafe(sourcePath)
  private val (targetBlockFlow, targetStorages) = Node.buildBlockFlowUnsafe(targetPath)

  Runtime.getRuntime.addShutdownHook(new Thread(() => {
    sourceStorages.closeUnsafe()
    targetStorages.closeUnsafe()
  }))

  new ReplayBlockFlow(sourceBlockFlow, targetBlockFlow).start() match {
    case Right(valid) =>
      if (valid) {
        logger.info("Replay blocks succeeded")
      } else {
        logger.error("All blocks replayed, but state hashes do not match")
      }
    case Left(error) =>
      logger.error(s"Replay blocks failed: $error")
  }
}
