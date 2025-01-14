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

import org.alephium.serde.{intSerde, Serde}
import org.alephium.util.AVector

final case class BlockHeightRange private (from: Int, to: Int, step: Int) {
  lazy val length: Int           = ((to - from) / step) + 1
  lazy val heights: AVector[Int] = AVector.tabulate(length)(at)

  def isValid(): Boolean = from >= 0 && to >= from && step >= 1

  def at(index: Int): Int = {
    assume(index < length)
    from + index * step
  }
}

object BlockHeightRange {
  implicit val serde: Serde[BlockHeightRange] =
    Serde.forProduct3(apply, v => (v.from, v.to, v.step))

  def from(from: Int, to: Int, step: Int): BlockHeightRange = {
    val range = BlockHeightRange(from, to, step)
    assume(range.isValid(), s"Invalid block height range: ${range}")
    range
  }
}
