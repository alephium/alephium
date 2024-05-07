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

import java.nio.file.{Files, Path, StandardCopyOption}

import scala.collection.mutable
import scala.reflect.io.Directory

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.Node
import org.alephium.flow.setting.Platform
import org.alephium.flow.validation.BlockValidation
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{Files => AFiles, TimeStamp}

object ReplayBlocks extends App with StrictLogging {
  private val sourcePath = Platform.getRootPath()
  private val replayPath = Utils.prepareReplayFolder(sourcePath)

  private val (sourceBlockFlow, sourceStorages) = Node.buildBlockFlowUnsafe(sourcePath)
  private val (targetBlockFlow, targetStorages) = Node.buildBlockFlowUnsafe(replayPath)
  Runtime.getRuntime.addShutdownHook(new Thread(() => {
    sourceStorages.closeUnsafe()
    targetStorages.closeUnsafe()
  }))

  private val chainIndexes = sourceBlockFlow.brokerConfig.chainIndexes
  private val brokerConfig = sourceBlockFlow.brokerConfig

  implicit class RightValue[L, R](either: Either[L, R]) {
    def value: R = either.toOption.get
  }

  val maxHeights =
    chainIndexes.map(chainIndex => sourceBlockFlow.getMaxHeight(chainIndex).value)
  val pendingBlocks =
    mutable.PriorityQueue.empty(Ordering.by[(Block, Int), TimeStamp](t => t._1.timestamp).reverse)

  private def loadBlocksAt(chainIndex: ChainIndex, height: Int): Unit = {
    val hashes = sourceBlockFlow.getHashes(chainIndex, height).value
    require(hashes.toArray.distinct.length == hashes.length, "")

    hashes.foreach(blockHash =>
      pendingBlocks.enqueue(sourceBlockFlow.getBlockUnsafe(blockHash) -> height)
    )
  }

  val startLoadingHeight = 1
  val loadedHeights      = chainIndexes.map(_ => startLoadingHeight).toArray
  chainIndexes.foreach(loadBlocksAt(_, startLoadingHeight))

  val validator = BlockValidation.build(targetBlockFlow)
  var count     = 0
  while (pendingBlocks.nonEmpty) {
    val (block, blockHeight) = pendingBlocks.dequeue()
    val chainIndex           = block.chainIndex

    // validate the block
    val sideEffect = validator.validate(block, targetBlockFlow).value
    targetBlockFlow.add(block, sideEffect).value

    // add new blocks to the queue
    val chainIndexOneDim = chainIndex.flattenIndex(brokerConfig)
    if (
      loadedHeights(chainIndexOneDim) == blockHeight && blockHeight < maxHeights(chainIndexOneDim)
    ) {
      loadBlocksAt(chainIndex, blockHeight + 1)
      loadedHeights(chainIndexOneDim) = blockHeight + 1
    }

    // stats
    count += 1
    if (count % 1000 == 0) {
      logger.info(s"Replayed #$count blocks")
    }
  }
}

object Utils {
  def prepareReplayFolder(sourcePath: Path): Path = {
    val replayPath = AFiles.homeDir.resolve(".alephium-replay")

    // Clear the folder
    new Directory(replayPath.toFile).deleteRecursively()
    replayPath.toFile.mkdir()

    Files.copy(
      sourcePath.resolve("user.conf"),
      replayPath.resolve("user.conf"),
      StandardCopyOption.REPLACE_EXISTING
    )
    replayPath
  }
}
