package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.alephium.flow.constant.{Consensus, Network}
import org.alephium.flow.model.ChainIndex
import org.alephium.protocol.model.Block
import org.alephium.serde.RandomBytes
import org.openjdk.jmh.annotations._

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class MiningBench {

  @Benchmark
  def mineGenesis(): Unit = {
    val nonce = RandomBytes.source.nextInt()
    val block = Block.genesis(Seq.empty, Consensus.maxMiningTarget, BigInt(nonce))
    val i     = RandomBytes.source.nextInt(Network.groups)
    val j     = RandomBytes.source.nextInt(Network.groups)
    ChainIndex(i, j).accept(block)
    ()
  }
}
