package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.alephium.crypto.{Keccak256, Sha256}
import org.openjdk.jmh.annotations._

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class HashBench {

  @Benchmark
  def randomKeccak256(): Keccak256 = {
    Keccak256.random
  }

  @Benchmark
  def randomSha256(): Sha256 = {
    Sha256.random
  }
}
