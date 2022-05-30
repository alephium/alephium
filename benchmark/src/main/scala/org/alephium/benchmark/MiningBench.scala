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

import scala.util.Random

import org.openjdk.jmh.annotations._

import org.alephium.flow.setting.{AlephiumConfig, Platform}
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.AVector

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
class MiningBench {

  val config: AlephiumConfig            = AlephiumConfig.load(Platform.getRootPath(), "alephium")
  implicit val groupConfig: GroupConfig = config.broker
  implicit val consensusConfig: ConsensusConfig = config.consensus

  @Benchmark
  def mineGenesis(): Boolean = {
    val block = Block.genesis(ChainIndex.unsafe(0, 0), AVector.empty)
    val i     = Random.nextInt(groupConfig.groups)
    val j     = Random.nextInt(groupConfig.groups)
    PoW.checkMined(block, ChainIndex.unsafe(i, j))
  }
}
