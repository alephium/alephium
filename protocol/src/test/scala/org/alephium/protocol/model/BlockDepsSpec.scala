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

package org.alephium.protocol.model

import org.alephium.crypto.Blake3
import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.protocol.model.BlockHash
import org.alephium.util.{AlephiumSpec, AVector}

class BlockDepsSpec extends AlephiumSpec with GroupConfigFixture.Default {

  it should "validate number of deps" in {
    val deps = AVector.tabulate(groupConfig.depsNum)(i => BlockHash(Blake3.hash(Seq(i.toByte))))
    assertThrows[IllegalArgumentException](BlockDeps.build(deps.init))
    assertThrows[IllegalArgumentException](BlockDeps.build(deps ++ deps))
  }

  it should "extract dependencies properly" in {
    val deps = AVector.tabulate(groupConfig.depsNum)(i => BlockHash(Blake3.hash(Seq(i.toByte))))
    val blockDeps = BlockDeps.build(deps)
    blockDeps.inDeps is deps.take(2)
    blockDeps.outDeps is deps.drop(2)
    for {
      i <- 0 until groups
      j <- 0 until groups
    } {
      val chainIndex = ChainIndex.unsafe(i, j)
      blockDeps.parentHash(chainIndex) is blockDeps.deps(2 + j)
      blockDeps.intraDep(chainIndex) is blockDeps.deps(2 + i)
    }
    for {
      i <- 0 until groups
    } {
      blockDeps.uncleHash(GroupIndex.unsafe(i)) is blockDeps.deps(2 + i)
      blockDeps.getOutDep(GroupIndex.unsafe(i)) is blockDeps.deps(2 + i)
    }
  }
}
