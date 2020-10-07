// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

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
