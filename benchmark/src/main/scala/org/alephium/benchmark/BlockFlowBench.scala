package org.alephium.benchmark

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.Storages
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.platform.Platform
import org.alephium.flow.setting.AlephiumConfig
import org.alephium.io.RocksDBSource
import org.alephium.protocol.model.GroupIndex

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
class BlockFlowBench {
  val rootPath: Path                  = Platform.getRootPath()
  implicit val config: AlephiumConfig = AlephiumConfig.load(rootPath).toOption.get
  private val storages: Storages = {
    val dbFolder = "db"
    Storages.createUnsafe(rootPath, dbFolder, RocksDBSource.Settings.writeOptions)(config.broker)
  }
  val blockFlow: BlockFlow = BlockFlow.fromGenesisUnsafe(config, storages)

  // TODO: benchmark blockheader verification

  @Benchmark
  def findBestDeps(): BlockDeps = {
    blockFlow.calBestDepsUnsafe(GroupIndex.unsafe(0)(config.broker))
  }
}
