package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.alephium.flow.setting.{AlephiumConfig, Platform}
import org.alephium.flow.validation.Validation
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, Random}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
class MiningBench {

  val config: AlephiumConfig            = AlephiumConfig.load(Platform.getRootPath()).toOption.get
  implicit val groupConfig: GroupConfig = config.broker

  @Benchmark
  def mineGenesis(): Boolean = {
    val nonce = Random.source.nextInt()
    val block = Block.genesis(AVector.empty, config.consensus.maxMiningTarget, BigInt(nonce))
    val i     = Random.source.nextInt(groupConfig.groups)
    val j     = Random.source.nextInt(groupConfig.groups)
    Validation.validateMined(block, ChainIndex.unsafe(i, j))
  }
}
