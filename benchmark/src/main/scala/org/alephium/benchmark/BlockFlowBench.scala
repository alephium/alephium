package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.GroupIndex

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class BlockFlowBench {

  implicit val profile: PlatformProfile = PlatformProfile.loadDefault()
  val blockFlow: BlockFlow              = BlockFlow.createUnsafe()(profile)

  // TODO: benchmark blockheader verification

  @Benchmark
  def findBestDeps(): BlockDeps = {
    blockFlow.calBestDepsUnsafe(GroupIndex(0))
  }
}
