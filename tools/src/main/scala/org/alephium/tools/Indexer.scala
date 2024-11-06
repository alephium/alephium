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

import scala.jdk.CollectionConverters._

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.Node
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, Configs, Platform}
import org.alephium.flow.validation.BlockValidation
import org.alephium.io.IOUtils
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.protocol.vm.WorldState
import org.alephium.util.{AVector, Env}

object Indexer extends App with StrictLogging {
  private val rootPath       = Platform.getRootPath()
  private val typesafeConfig = Configs.parseConfigAndValidate(Env.Prod, rootPath, overwrite = true)
  private val config         = AlephiumConfig.load(typesafeConfig, "alephium")
  private val brokerConfig   = config.broker
  private val intraChainIndexes = brokerConfig.chainIndexes.filter(_.isIntraGroup)
  private val indexedBlockCount = new AtomicInteger(0)

  private lazy val (blockFlow, storages) = Node.buildBlockFlowUnsafe(rootPath)
  private lazy val totalBlockCount =
    intraChainIndexes.map(chainIndex => blockFlow.getBlockChain(chainIndex).numHashes).sum

  private def clearIndexStorage(): Unit = {
    val dbPath  = rootPath.resolve(config.network.networkId.nodeFolder)
    val rocksdb = Storages.createRocksDBUnsafe(dbPath, "db")
    val indexColumnFamilies: AVector[ColumnFamily] = AVector(
      ColumnFamily.Log,
      ColumnFamily.LogCounter,
      ColumnFamily.TxOutputRefIndex,
      ColumnFamily.ParentContract,
      ColumnFamily.SubContract,
      ColumnFamily.SubContractCounter
    )
    IOUtils.tryExecute {
      rocksdb.db.dropColumnFamilies(indexColumnFamilies.map(rocksdb.handle).toSeq.asJava)
      rocksdb.closeUnsafe()
    } match {
      case Right(_)    => ()
      case Left(error) => exit(s"Failed to clear index storage due to $error")
    }
  }

  private def checkConfig(): Unit = {
    val nodeSettings = config.node
    if (
      !nodeSettings.eventLogConfig.enabled &&
      !nodeSettings.indexesConfig.subcontractIndex &&
      !nodeSettings.indexesConfig.txOutputRefIndex
    ) {
      exit("The index configs is not enabled")
    }
  }

  private def exit(msg: String) = {
    logger.error(msg)
    System.exit(-1)
  }

  private def indexBlock(worldState: WorldState.Cached, block: Block): Unit = {
    blockFlow
      .updateState(worldState, block)
      .flatMap(_ => worldState.nodeIndexesState.persist())
      .left
      .foreach(error => exit(s"IO error when indexing block ${block.hash.toHexString}: $error"))
  }

  private def index(chainIndex: ChainIndex): Unit = {
    assume(chainIndex.isIntraGroup)
    val validator = BlockValidation.build(blockFlow)
    val chain     = blockFlow.getBlockChain(chainIndex)
    IOUtils
      .tryExecute {
        val maxHeight = chain.maxHeightByWeightUnsafe
        (ALPH.GenesisHeight + 1 to maxHeight).foreach { height =>
          val hashes = chain.getHashesUnsafe(height)
          hashes.map(chain.getBlockUnsafe).foreach { block =>
            validator
              .validate(block, blockFlow)
              .map {
                case Some(worldState) => indexBlock(worldState, block)
                case None             => ()
              }
              .left
              .foreach(error => exit(s"failed to index block ${block.hash.toHexString}: $error"))
          }

          val count = indexedBlockCount.addAndGet(hashes.length)
          if (count % 10000 == 0) {
            val progress = (count.toDouble / totalBlockCount.toDouble) * 100
            print(s"Indexed #$count blocks, progress: ${f"$progress%.0f%%"}\n")
          }
        }
      }
      .left
      .foreach(error => exit(s"IO error when indexing blocks: $error"))
  }

  private def indexGenesis(): Unit = {
    config.genesisBlocks.foreach(_.foreach { block =>
      val chainIndex = block.chainIndex
      if (chainIndex.isIntraGroup) {
        val worldState = storages.emptyWorldState.cached()
        indexBlock(worldState, block)
      }
    })
  }

  private def start(): Unit = {
    assume(intraChainIndexes.length == brokerConfig.groups)
    checkConfig()
    clearIndexStorage()

    indexGenesis()

    val threads = brokerConfig.groupRange.map { group =>
      new Thread(() => index(ChainIndex.unsafe(group, group)(brokerConfig)))
    }
    threads.foreach(_.start())
    threads.foreach(_.join)

    print("Indexing blocks completed\n")

    storages.close() match {
      case Right(_)    => ()
      case Left(error) => exit(s"Failed to close the storage due to $error")
    }
  }

  start()
}
