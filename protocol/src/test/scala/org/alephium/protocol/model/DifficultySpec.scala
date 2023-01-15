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

import org.scalacheck.Gen

import org.alephium.util.AlephiumSpec

class DifficultySpec extends AlephiumSpec {
  it should "convert to Target" in {
    (1 until 256).foreach { k =>
      val diff = Difficulty.unsafe(BigInteger.ONE.shiftLeft(k))
      diff.getTarget().getDifficulty() is diff // No precision lost
      diff.getTarget().value is Target.maxBigInt.divide(diff.value)
    }

    info("Precision might be lost")
    val diff = Difficulty.unsafe(BigInteger.valueOf(33333333333L))
    diff.getTarget().getDifficulty() isnot diff
  }

  it should "scale difficulty" in {
    forAll(Gen.posNum[Int], Gen.posNum[Int]) { (n: Int, m: Int) =>
      val diff = Difficulty.unsafe(n)
      diff.divide(m) is Difficulty.unsafe(n / m)
      diff.times(m) is Difficulty.unsafe(n * m)
    }
  }
}
