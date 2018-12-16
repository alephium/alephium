package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.alephium.flow.PlatformConfig
import org.alephium.flow.constant.Consensus
import org.alephium.flow.model.ChainIndex
import org.alephium.protocol.model.Block
import org.alephium.serde.RandomBytes
import org.alephium.util.AVector
import org.openjdk.jmh.annotations._

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class MiningBench {
  import PlatformConfig.Default.config

  @Benchmark
  def mineGenesis(): Boolean = {
    val nonce = RandomBytes.source.nextInt()
    val block = Block.genesis(AVector.empty, Consensus.maxMiningTarget, BigInt(nonce))
    val i     = RandomBytes.source.nextInt(config.groups)
    val j     = RandomBytes.source.nextInt(config.groups)
    ChainIndex(i, j).accept(block)(config)
  }
}
