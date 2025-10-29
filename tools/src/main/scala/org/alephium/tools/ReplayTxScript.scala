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

import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec

import org.alephium.flow.client.Node
import org.alephium.flow.setting.Platform
import org.alephium.flow.validation._
import org.alephium.io.IOResult
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.config.{BrokerConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{BlockEnv, LogConfig}

object ReplayTxScript extends App {
  private val rootPath                              = Platform.getRootPath()
  private val (blockFlow, storages)                 = Node.buildBlockFlowUnsafe(rootPath)
  implicit private val brokerConfig: BrokerConfig   = blockFlow.brokerConfig
  implicit private val networkConfig: NetworkConfig = blockFlow.networkConfig
  implicit private val logConfig: LogConfig         = blockFlow.logConfig
  private val txValidation: TxValidation            = TxValidation.build

  Runtime.getRuntime.addShutdownHook(new Thread(() => storages.closeUnsafe()))

  private val maxHeightPerChain = (0 until brokerConfig.groups).map { index =>
    val groupIndex = GroupIndex.unsafe(index)
    val chainIndex = ChainIndex(groupIndex, groupIndex)
    blockFlow.getMaxHeightByWeight(chainIndex) match {
      case Right(height) => height
      case Left(error)   => exitOnError(s"Failed to get max height due to IO error $error")
    }
  }
  private val executedCount = new AtomicInteger(0)
  private val totalCount    = maxHeightPerChain.sum

  private val initHeight        = ALPH.GenesisHeight + 1
  private var currentGroupIndex = 0
  private var currentHeight     = initHeight

  private val threads = (0 until 4).map(_ => new Thread(() => replayBlocks()))
  threads.foreach(_.start())
  threads.foreach(_.join())
  print("Replay completed\n")

  @tailrec private def replayBlocks(): Unit = {
    nextHeightRange() match {
      case Some((group, from, to)) =>
        val chainIndex = ChainIndex(group, group)
        (from until to).foreach(replayBlock(chainIndex, _))
        val count    = executedCount.addAndGet(to - from)
        val progress = (count.toDouble / totalCount.toDouble) * 100
        print(s"Executed #$count blocks, progress: ${f"$progress%.0f%%"}\n")
        replayBlocks()
      case None => ()
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def nextHeightRange(): Option[(GroupIndex, Int, Int)] = this.synchronized {
    if (currentGroupIndex >= brokerConfig.groups) {
      None
    } else if (currentHeight >= maxHeightPerChain(currentGroupIndex)) {
      currentGroupIndex += 1
      currentHeight = initHeight
      nextHeightRange()
    } else {
      val fromHeight = currentHeight
      currentHeight += 10000
      val maxHeight = maxHeightPerChain(currentGroupIndex) + 1
      val toHeight  = math.min(currentHeight, maxHeight)
      Some((GroupIndex.unsafe(currentGroupIndex), fromHeight, toHeight))
    }
  }

  private def replayBlock(chainIndex: ChainIndex, height: Int): Unit = {
    val blockchain = blockFlow.getBlockChain(chainIndex)
    val result = for {
      blockHash <- blockchain.getHashes(height).map(_.head)
      block     <- blockchain.getBlock(blockHash)
      _ <-
        if (block.nonCoinbase.exists(_.unsigned.scriptOpt.isDefined)) {
          replayAndCheck(block)
        } else {
          Right(())
        }
    } yield ()
    result.left.foreach { error =>
      exitOnError(
        s"IO error occurred when replaying block: $error, chain index: $chainIndex, height: $height"
      )
    }
  }

  private def replayAndCheck(block: Block): IOResult[Unit] = {
    for {
      expected  <- storages.worldStateStorage.get(block.hash).map(_.contractStateHash)
      stateHash <- replayBlock(block)
    } yield {
      if (stateHash != expected) {
        exitOnError(
          s"State hash mismatch: expected ${expected.toHexString}, got ${stateHash.toHexString}, block hash: ${block.hash.toHexString}"
        )
      }
    }
  }

  private def replayBlock(block: Block): IOResult[Hash] = {
    val hardFork = networkConfig.getHardFork(block.timestamp)
    val executionOrder =
      Block.getNonCoinbaseExecutionOrder(block.parentHash, block.nonCoinbase, hardFork)
    val blockEnv              = BlockEnv.from(block.chainIndex, block.header)
    val sequentialTxSupported = ALPH.isSequentialTxSupported(block.chainIndex, hardFork)
    blockFlow
      .getMutableGroupView(block.chainIndex, block.blockDeps, hardFork, Some(block.hash))
      .flatMap { groupView =>
        executionOrder.foreach { index =>
          val tx = block.transactions(index)
          val result = tx.unsigned.scriptOpt match {
            case Some(_) =>
              txValidation.checkBlockTx(block.chainIndex, tx, groupView, blockEnv, None, index)
            case None => Right(())
          }
          result match {
            case Right(_)                                                       => ()
            case Left(Right(TxScriptExeFailed(_))) if tx.contractInputs.isEmpty => ()
            case Left(error) =>
              exitOnError(s"Failed to validate tx ${tx.id.toHexString} due to $error")
          }
          if (sequentialTxSupported) blockEnv.addOutputRefFromTx(tx.unsigned)
        }
        groupView.worldState.contractState.getRootHash()
      }
  }

  private def exitOnError(error: String): Nothing = {
    print(s"$error\n")
    sys.exit(1)
  }
}
