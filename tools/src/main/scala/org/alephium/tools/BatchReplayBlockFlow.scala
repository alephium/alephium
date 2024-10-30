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

import scala.collection.mutable.ArrayBuffer
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
import org.alephium.protocol.model.Block
import org.alephium.util.{ActorRefT, AVector, Duration, Env, EventBus, Files => AFiles}

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

  private val (sourceBlockFlow, sourceStorages, _) = Node.buildBlockFlowUnsafe(sourcePath)
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
    val sourceBlockFlow: BlockFlow,
    val targetBlockFlow: BlockFlow,
    dependencyHandler: ActorRefT[DependencyHandler.Command],
    pendingCapacity: Int
) extends IOBaseActor
    with ReplayState {
  private var pendingBlocks = 0

  private def replayUnsafe(): Unit = {
    val blocks = ArrayBuffer.empty[Block]
    while (blockQueue.nonEmpty && blocks.length < 5) {
      val (block, blockHeight) = blockQueue.dequeue()
      val chainIndex           = block.chainIndex
      blocks.addOne(block)
      loadMoreBlocksUnsafe(chainIndex, blockHeight)
      pendingBlocks += 1
    }

    if (blocks.nonEmpty) {
      dependencyHandler ! DependencyHandler.AddFlowData(AVector.from(blocks), DataOrigin.Local)
    }
  }

  private def checkStateHashes(): IOResult[Unit] = {
    isStateHashesSame.map { isSame =>
      if (isSame) {
        print("Replay blocks succeeded\n")
      } else {
        log.error("All blocks replayed, but state hashes do not match")
      }
    }
  }

  override def preStart(): Unit = {
    escapeIOError(init())
    escapeIOError(IOUtils.tryExecute(replayUnsafe()))
    super.preStart()
  }

  def receive: Receive = {
    case _: BlockChainHandler.BlockAdded =>
      replayedBlockCount += 1
      if (replayedBlockCount % ReplayState.LogInterval == 0) {
        val (speed, cycleSpeed) = calcSpeed()
        print(s"Replayed #$replayedBlockCount blocks, #$speed BPS, #$cycleSpeed cycle BPS\n")
      }
      pendingBlocks -= 1
      if (pendingBlocks < pendingCapacity) {
        escapeIOError(IOUtils.tryExecute(replayUnsafe()))
      }
      if (pendingBlocks == 0) {
        escapeIOError(checkStateHashes())
        context.system.terminate()
        ()
      }
    case msg => log.warning(s"Unknown message $msg")
  }
}
