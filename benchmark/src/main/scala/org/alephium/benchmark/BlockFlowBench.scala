package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.alephium.flow.constant.Network
import org.alephium.flow.model.{BlockDeps, ChainIndex}
import org.alephium.flow.storage.BlockFlow
import org.alephium.serde.RandomBytes
import org.openjdk.jmh.annotations._

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class BlockFlowBench {
  val blockFlow: BlockFlow = BlockFlow()

  // TODO: benchmark blockheader verification

  @Benchmark
  def findBestDeps(): BlockDeps = {
    val i = RandomBytes.source.nextInt(Network.groups)
    val j = RandomBytes.source.nextInt(Network.groups)
    blockFlow.getBestDeps(ChainIndex(i, j))
  }
}
