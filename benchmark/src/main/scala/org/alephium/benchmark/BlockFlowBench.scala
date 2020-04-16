package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.RocksDBSource.Settings
import org.alephium.flow.io.Storages
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.GroupIndex

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class BlockFlowBench {

  implicit val config: PlatformConfig = PlatformConfig.loadDefault()
  val storages: Storages = {
    val dbFolder = "db"
    val dbName   = s"${config.brokerInfo.id}-${config.publicAddress.getPort}"
    Storages.createUnsafe(config.rootPath, dbFolder, dbName, Settings.writeOptions)
  }
  val blockFlow: BlockFlow = BlockFlow.fromGenesisUnsafe(storages)(config)

  // TODO: benchmark blockheader verification

  @Benchmark
  def findBestDeps(): BlockDeps = {
    blockFlow.calBestDepsUnsafe(GroupIndex.unsafe(0))
  }
}
