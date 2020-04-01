package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.alephium.crypto.Sha256
import org.alephium.protocol.ALF.Hash

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class HashBench {

  @Benchmark
  def randomHash(): Hash = {
    Hash.random
  }

  @Benchmark
  def randomSha256(): Sha256 = {
    Sha256.random
  }
}
