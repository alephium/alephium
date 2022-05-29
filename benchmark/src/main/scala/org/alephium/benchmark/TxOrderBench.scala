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

import org.alephium.protocol.{BlockHash, Hash, Signature}
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.StatefulScript
import org.alephium.util.{AVector, TimeStamp}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class TxOrderBench {
  implicit val groupConfig: GroupConfig = new GroupConfig {
    override def groups: Int = 4
  }
  implicit val networkConfig: NetworkConfig = new NetworkConfig {
    val networkId: NetworkId              = NetworkId(0)
    val noPreMineProof: ByteString        = ByteString.empty
    val lemanHardForkTimestamp: TimeStamp = TimeStamp.now()
  }
  val txNum: Int = 2000
  val header: BlockHeader =
    BlockHeader.unsafeWithRawDeps(
      AVector.fill(groupConfig.depsNum)(BlockHash.zero),
      Hash.zero,
      Hash.zero,
      TimeStamp.zero,
      Target.Max,
      Nonce.zero
    )
  val txs: AVector[Transaction] =
    AVector.fill(txNum)(
      Transaction.from(
        UnsignedTransaction(
          Some(StatefulScript.unsafe(AVector.empty)),
          AVector.empty,
          AVector.empty
        ),
        AVector.empty[Signature]
      )
    )
  val block: Block = Block(header, txs)

  @Benchmark
  def calculateRandomOrder(bh: Blackhole): Unit = {
    bh.consume(block.getScriptExecutionOrder)
  }
}
