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

import org.alephium.crypto.{Blake2b, Keccak256, SecP256K1, Sha256}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class CryptoBench {
  val data: ByteString = Blake2b.generate.bytes

  private val (privateKey, publicKey) = SecP256K1.generatePriPub()
  private val signature               = SecP256K1.sign(data, privateKey)
  require(SecP256K1.verify(data, signature, publicKey))

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

  @Benchmark
  def secp256k1(bh: Blackhole): Unit = {
    bh.consume(SecP256K1.verify(data, signature, publicKey))
  }
}
