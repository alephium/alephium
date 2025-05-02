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

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.Node
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.setting.Platform
import org.alephium.flow.validation.{BlockValidation, BlockValidationResult, GhostUncleDoesNotExist}
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.model.{Block, BlockHash, GroupIndex}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{Files => AFiles}

class ReplayBlockFlow(
    val sourceBlockFlow: BlockFlow,
    val targetBlockFlow: BlockFlow
) extends ReplayState
    with StrictLogging {
  private val validator = BlockValidation.build(targetBlockFlow)

  def start(updateFlowSkeleton: Boolean): BlockValidationResult[Boolean] = {
    for {
      _      <- from(init())
      _      <- if (updateFlowSkeleton) replayAndUpdateFlowSkeleton() else replay()
      isSame <- from(isStateHashesSame)
    } yield isSame
  }

  private def printReplayProgress(): Unit = {
    replayedBlockCount += 1
    if (replayedBlockCount % ReplayState.LogInterval == 0) {
      val (speed, cycleSpeed) = calcSpeed()
      print(s"Replayed #$replayedBlockCount blocks, #$speed BPS, #$cycleSpeed cycle BPS\n")
    }
  }

  private def validateAndAddBlock(hash: BlockHash): BlockValidationResult[Unit] = {
    for {
      block <- from(sourceBlockFlow.getBlock(hash))
      _     <- validateAndAddBlock(block)
    } yield ()
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def validateAndAddBlock(block: Block): BlockValidationResult[Unit] = {
    (for {
      sideEffect <- validator.validate(block, targetBlockFlow)
      _          <- from(targetBlockFlow.add(block, sideEffect))
    } yield ()) match {
      case Left(Right(GhostUncleDoesNotExist(uncleHash))) =>
        for {
          _ <- validateAndAddBlock(uncleHash)
          _ <- validateAndAddBlock(block)
        } yield ()
      case result => result
    }
  }

  private def handleBlock(block: Block, height: Int): BlockValidationResult[Unit] = {
    for {
      exist <- from(targetBlockFlow.contains(block.hash))
      _     <- if (!exist) validateAndAddBlock(block) else Right(())
      _     <- from(IOUtils.tryExecute(loadMoreBlocksUnsafe(block.chainIndex, height)))
    } yield ()
  }

  private def replay(): BlockValidationResult[Unit] = {
    var result = Right(()): BlockValidationResult[Unit]

    while (blockQueue.nonEmpty && result.isRight) {
      val (block, blockHeight) = blockQueue.dequeue()
      result = handleBlock(block, blockHeight)
      printReplayProgress()
    }

    result
  }

  private lazy val miners = Array.tabulate(brokerConfig.groups) { group =>
    val groupIndex = GroupIndex.unsafe(group)(brokerConfig)
    LockupScript.p2pkh(groupIndex.generateKey(brokerConfig)._2)
  }

  private def handleBlockAndUpdateFlowSkeleton(
      block: Block,
      height: Int
  ): BlockValidationResult[Unit] = {
    val chainIndex = block.chainIndex
    for {
      _ <- handleBlock(block, height)
      _ <- from(targetBlockFlow.updateBestFlowSkeleton())
      _ <- from(targetBlockFlow.prepareBlockFlow(chainIndex, miners(chainIndex.to.value)))
    } yield ()
  }

  private def replayAndUpdateFlowSkeleton(): BlockValidationResult[Unit] = {
    var result = Right(()): BlockValidationResult[Unit]

    while (blockQueue.nonEmpty && result.isRight) {
      val (block, blockHeight) = blockQueue.dequeue()
      result = handleBlockAndUpdateFlowSkeleton(block, blockHeight)
      printReplayProgress()
    }

    result
  }

  private def from[T](result: IOResult[T]): BlockValidationResult[T] = {
    result.left.map(Left(_))
  }
}

object ReplayBlockFlow extends App with StrictLogging {
  private val updateFlowSkeleton = if (args.length == 0) {
    false
  } else if (args.length == 1 && args(0) == "updateFlowSkeleton") {
    true
  } else {
    logger.error(s"Invalid args $args")
    sys.exit(1)
  }

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

  new ReplayBlockFlow(sourceBlockFlow, targetBlockFlow).start(updateFlowSkeleton) match {
    case Right(valid) =>
      if (valid) {
        print("Replay blocks succeeded\n")
      } else {
        logger.error("All blocks replayed, but state hashes do not match")
      }
    case Left(error) =>
      logger.error(s"Replay blocks failed: $error")
  }
}
