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

import java.math.BigInteger

final case class Difficulty private (value: BigInteger) extends AnyVal {
  // WARN: use it with caution because this conversion might lose precision
  // i.e. `diff.getTarget().getDifficulty()` might not be equal to `diff`
  def getTarget(): Target = {
    if (value == BigInteger.ONE) {
      Target.Max
    } else {
      Target.unsafe(Target.maxBigInt.divide(value))
    }
  }

  def times(n: Int): Difficulty = Difficulty.unsafe(value.multiply(BigInteger.valueOf(n.toLong)))

  def divide(n: Int): Difficulty = new Difficulty(value.divide(BigInteger.valueOf(n.toLong)))
}

object Difficulty {
  def unsafe(value: BigInteger): Difficulty = new Difficulty(value)

  val zero: Difficulty = Difficulty(BigInteger.valueOf(0))
}
