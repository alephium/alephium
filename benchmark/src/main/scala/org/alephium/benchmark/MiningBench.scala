package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.alephium.flow.platform.PlatformConfig
import org.alephium.flow.validation.Validation
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, Random}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class MiningBench {

  implicit val config: PlatformConfig = PlatformConfig.loadDefault()

  @Benchmark
  def mineGenesis(): Boolean = {
    val nonce = Random.source.nextInt()
    val block = Block.genesis(AVector.empty, config.maxMiningTarget, BigInt(nonce))
    val i     = Random.source.nextInt(config.groups)
    val j     = Random.source.nextInt(config.groups)
    Validation.validateMined(block, ChainIndex.unsafe(i, j))
  }
}
