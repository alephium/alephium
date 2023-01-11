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

package org.alephium.protocol.config

import java.math.BigInteger

import org.alephium.protocol.model.{ChainIndex, GroupIndex}
import org.alephium.util.AVector

trait GroupConfig {
  def groups: Int

  lazy val chainNum: Int = groups * groups

  lazy val depsNum: Int = 2 * groups - 1

  lazy val cliqueGroups: AVector[GroupIndex] = AVector.tabulate(groups)(GroupIndex.unsafe(_)(this))

  lazy val targetAverageCount: BigInteger = BigInteger.valueOf((4 * groups).toLong)

  lazy val cliqueChainIndexes: AVector[ChainIndex] =
    AVector.tabulate(chainNum)(ChainIndex.unsafe(_)(this))

  lazy val cliqueGroupIndexes: AVector[GroupIndex] =
    AVector.tabulate(groups)(GroupIndex.unsafe(_)(this))
}
