package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import akka.util.ByteString
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import org.alephium.crypto.{Blake2b, Keccak256, Sha256}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class HashBench {
  val data: ByteString = ByteString.fromArrayUnsafe(Array.fill(1 << 10)(0))

  @Benchmark
  def black2b(bh: Blackhole): Unit = {
    bh.consume(Blake2b.hash(data))
  }

  @Benchmark
  def keccak256(bh: Blackhole): Unit = {
    bh.consume(Keccak256.hash(data))
  }

  @Benchmark
  def sha256(bh: Blackhole): Unit = {
    bh.consume(Sha256.hash(data))
  }
}
