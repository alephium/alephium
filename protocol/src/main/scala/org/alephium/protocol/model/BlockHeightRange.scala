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
  private lazy val validLength: Option[Int] = {
    if (from >= 0 && to >= from && step >= 1) {
      val length = ((to.toLong - from.toLong) / step.toLong) + 1
      Option.when(length <= Int.MaxValue)(length.toInt)
    } else {
      None
    }
  }

  lazy val length: Int           = validLength.getOrElse(0)
  lazy val heights: AVector[Int] = AVector.tabulate(length)(at)

  def isValid(): Boolean = validLength.nonEmpty

  def isValid(maxLength: Int): Boolean = {
    maxLength >= 1 && validLength.exists(_ <= maxLength)
  }

  def at(index: Int): Int = {
    assume(index >= 0 && index < length)
    (from.toLong + index.toLong * step.toLong).toInt
  }
}

object BlockHeightRange {
  implicit val serde: Serde[BlockHeightRange] =
    Serde
      .forProduct3[Int, Int, Int, BlockHeightRange](apply, v => (v.from, v.to, v.step))
      .validate(range => Either.cond(range.isValid(), (), s"Invalid block height range: $range"))

  def from(from: Int, to: Int, step: Int): BlockHeightRange = {
    val range = BlockHeightRange(from, to, step)
    assume(range.isValid(), s"Invalid block height range: ${range}")
    range
  }

  def fromHeight(height: Int): BlockHeightRange = from(height, height, 1)
}
