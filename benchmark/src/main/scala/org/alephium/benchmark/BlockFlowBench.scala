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

package org.alephium.benchmark

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, Platform}
import org.alephium.io.RocksDBSource
import org.alephium.protocol.model.{BlockDeps, GroupIndex}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
class BlockFlowBench {
  val rootPath: Path                  = Platform.getRootPath()
  implicit val config: AlephiumConfig = AlephiumConfig.load(rootPath, "alephium")
  private val storages: Storages = {
    val dbFolder = "db"
    Storages.createUnsafe(rootPath, dbFolder, RocksDBSource.ProdSettings.writeOptions)(
      config.broker,
      config.node
    )
  }
  val blockFlow: BlockFlow = BlockFlow.fromGenesisUnsafe(config, storages)

  // TODO: benchmark blockheader verification

  @Benchmark
  def findBestDeps(): BlockDeps = {
    blockFlow.calBestDepsUnsafe(GroupIndex.unsafe(0)(config.broker))
  }
}
