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

import org.slf4j.{Logger, LoggerFactory}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.PruneStorageService
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, Configs}
import org.alephium.io.IOResult
import org.alephium.io.RocksDBSource.Settings
import org.alephium.protocol.config.GroupConfig
import org.alephium.util.{Env, Files}
import org.alephium.util.BloomFilter

object PruneStorage extends App {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private val rootPath       = Files.homeDir.resolve(".alephium/mainnet")
  private val typesafeConfig = Configs.parseConfigAndValidate(Env.Prod, rootPath, overwrite = true)
  private val config         = AlephiumConfig.load(typesafeConfig, "alephium")
  private val storages = Storages.createUnsafe(rootPath, "db", Settings.writeOptions)(config.broker)
  private val blockFlow   = BlockFlow.fromStorageUnsafe(config, storages)
  private val groupConfig = new GroupConfig { override def groups: Int = 4 }

  private val pruneStateService = new PruneStorageService(storages)(blockFlow, groupConfig)

  val bloomFilterResult: IOResult[BloomFilter] = pruneStateService.buildBloomFilter()

  bloomFilterResult match {
    case Right(bloomFilter) =>
      val (totalCount, pruneCount) = pruneStateService.prune(bloomFilter)
      logger.info(s"[FINAL] totalCount: ${totalCount}, pruneCount: ${pruneCount}")
    case Left(error) =>
      logger.info(s"error: ${error}")
  }
}
