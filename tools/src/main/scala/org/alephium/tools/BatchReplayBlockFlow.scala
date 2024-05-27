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

import scala.collection.mutable.{ArrayBuffer, PriorityQueue}
import scala.concurrent.Await

import akka.actor.{ActorSystem, Props}
import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.Node
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, BlockChainHandler, DependencyHandler, IOBaseActor}
import org.alephium.flow.io.Storages
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.setting.{AlephiumConfig, Configs, Platform}
import org.alephium.io.{IOResult, IOUtils, RocksDBSource}
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.protocol.vm.WorldState
import org.alephium.util.{ActorRefT, AVector, Duration, Env, EventBus, Files => AFiles, TimeStamp}

object BatchReplayBlockFlow extends App with StrictLogging {
  private val sourcePath = Platform.getRootPath()
  private val targetPath = {
    val path = AFiles.homeDir.resolve(".alephium-batch-replay")
    path.toFile.mkdir()
    Files.copy(
      sourcePath.resolve("user.conf"),
      path.resolve("user.conf"),
      StandardCopyOption.REPLACE_EXISTING
    )
    path
  }

  private def buildTargetBlockFlowUnsafe() = {
    val typesafeConfig =
      Configs.parseConfigAndValidate(Env.Prod, targetPath, overwrite = true)
    val config = AlephiumConfig.load(typesafeConfig, "alephium")
    val dbPath = targetPath.resolve(config.network.networkId.nodeFolder)
    val storages =
      Storages.createUnsafe(dbPath, "db", RocksDBSource.ProdSettings.writeOptions)(
        config.broker,
        config.node
      )
    val flowSystem = ActorSystem("flow", typesafeConfig)
    val eventBus: ActorRefT[EventBus.Message] =
      ActorRefT.build[EventBus.Message](flowSystem, EventBus.props())
    val blockFlow = Node.buildBlockFlowUnsafe(storages)(config)
    val allHandlers = AllHandlers.build(flowSystem, blockFlow, eventBus, storages)(
      config.broker,
      config.consensus,
      config.network,
      config.mining,
      config.mempool,
      config.node.eventLogConfig
    )
    (blockFlow, storages, allHandlers, flowSystem)
  }

  private val (sourceBlockFlow, sourceStorages) = Node.buildBlockFlowUnsafe(sourcePath)
  private val (targetBlockFlow, targetStorages, targetHandlers, flowSystem) =
    buildTargetBlockFlowUnsafe()

  Runtime.getRuntime.addShutdownHook(new Thread(() => {
    sourceStorages.closeUnsafe()
    targetStorages.closeUnsafe()
    Await.result(flowSystem.terminate(), Duration.ofSecondsUnsafe(10).asScala)
    ()
  }))

  // scalastyle:off magic.number
  private val pendingCapacity = args.headOption.flatMap(_.toIntOption).getOrElse(100)
  // scalastyle:on magic.number
  flowSystem.actorOf(
    Replayer.props(
      sourceBlockFlow,
      targetBlockFlow,
      targetHandlers.dependencyHandler,
      pendingCapacity
    )
  )
}

object Replayer {
  def props(
      sourceBlockFlow: BlockFlow,
      targetBlockFlow: BlockFlow,
      dependencyHandler: ActorRefT[DependencyHandler.Command],
      pendingCapacity: Int
  ): Props = {
    Props(new Replayer(sourceBlockFlow, targetBlockFlow, dependencyHandler, pendingCapacity))
  }
}

final class Replayer(
    sourceBlockFlow: BlockFlow,
    targetBlockFlow: BlockFlow,
    dependencyHandler: ActorRefT[DependencyHandler.Command],
    pendingCapacity: Int
) extends IOBaseActor {
  private val startLoadingHeight = 1
  private val brokerConfig       = sourceBlockFlow.brokerConfig
  private val chainIndexes       = brokerConfig.chainIndexes
  private val loadedHeights      = chainIndexes.map(_ => startLoadingHeight).toArray
  private val maxHeights = chainIndexes.map(chainIndex =>
    sourceBlockFlow.getMaxHeightByWeight(chainIndex) match {
      case Left(error)   => throw error
      case Right(height) => height
    }
  )

  private val blockQueue =
    PriorityQueue.empty(Ordering.by[(Block, Int), TimeStamp](t => t._1.timestamp).reverse)
  private var replayedBlockCount = 0
  private var pendingBlocks      = 0

  private val startTs = TimeStamp.now()
  private var countTs = TimeStamp.now()

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

  private def loadInitialBlocks(): IOResult[Unit] = {
    chainIndexes.foreachE { chainIndex =>
      val chainIndexOneDim = chainIndex.flattenIndex(brokerConfig)
      for {
        height0 <- targetBlockFlow.getMaxHeightByWeight(chainIndex)
        height = if (height0 > startLoadingHeight) height0 + 1 else startLoadingHeight
        _ <- IOUtils.tryExecute(loadBlocksAtUnsafe(chainIndex, height))
      } yield {
        loadedHeights(chainIndexOneDim) = height
      }
    }
  }

  private def loadMoreBlocksUnsafe(
      chainIndex: ChainIndex,
      maxHeights: AVector[Int],
      blockHeight: Int
  ): Unit = {
    val chainIndexOneDim = chainIndex.flattenIndex(brokerConfig)
    val shouldLoadMore =
      loadedHeights(chainIndexOneDim) == blockHeight && blockHeight < maxHeights(
        chainIndexOneDim
      )

    if (shouldLoadMore) {
      loadBlocksAtUnsafe(chainIndex, blockHeight + 1)
      loadedHeights(chainIndexOneDim) = blockHeight + 1
    }
  }

  private def replayUnsafe(maxHeights: AVector[Int]): Unit = {
    val blocks = ArrayBuffer.empty[Block]
    while (blockQueue.nonEmpty && blocks.length < pendingCapacity) {
      val (block, blockHeight) = blockQueue.dequeue()
      val chainIndex           = block.chainIndex
      blocks.addOne(block)
      loadMoreBlocksUnsafe(chainIndex, maxHeights, blockHeight)
      pendingBlocks += 1
    }

    if (blocks.nonEmpty) {
      dependencyHandler ! DependencyHandler.AddFlowData(AVector.from(blocks), DataOrigin.Local)
    }
  }

  private def fetchBestWorldStateHashes(
      blockFlow: BlockFlow
  ): IOResult[AVector[WorldState.Hashes]] = {
    blockFlow.brokerConfig.cliqueGroupIndexes.mapE { groupIndex =>
      blockFlow.getBestPersistedWorldState(groupIndex).map(_.toHashes)
    }
  }

  private def checkStateHashes(): IOResult[Unit] = {
    for {
      sourceHashes <- fetchBestWorldStateHashes(sourceBlockFlow)
      targetHashes <- fetchBestWorldStateHashes(targetBlockFlow)
    } yield {
      if (sourceHashes == targetHashes) {
        log.info("Replay blocks succeeded")
      } else {
        log.error("All blocks replayed, but state hashes do not match")
      }
    }
  }

  override def preStart(): Unit = {
    escapeIOError(loadInitialBlocks())
    replayedBlockCount = loadedHeights.map(_ - 1).sum
    escapeIOError(IOUtils.tryExecute(replayUnsafe(maxHeights)))
  }

  def receive: Receive = {
    case _: BlockChainHandler.BlockAdded =>
      replayedBlockCount += 1
      if (replayedBlockCount % 1000 == 0) {
        val now           = TimeStamp.now()
        val eclipsed      = now.deltaUnsafe(startTs).millis
        val speed         = replayedBlockCount * 1000 / eclipsed
        val cycleEclipsed = now.deltaUnsafe(countTs).millis
        val cycleSpeed    = 1000 * 1000 / cycleEclipsed
        countTs = now
        log.info(s"Replayed #$replayedBlockCount blocks, #$speed BPS, #$cycleSpeed cycle BPS")
      }
      pendingBlocks -= 1
      if (pendingBlocks < pendingCapacity) {
        escapeIOError(IOUtils.tryExecute(replayUnsafe(maxHeights)))
      }
      if (pendingBlocks == 0) {
        escapeIOError(checkStateHashes())
        context.system.terminate()
        ()
      }
    case msg => log.warning(s"Unknown message $msg")
  }
}
