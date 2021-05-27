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

import org.alephium.protocol.mining.HashRate
import org.alephium.util.{AlephiumSpec, Duration}

class WeightSpec extends AlephiumSpec {
  it should "check special values" in {
    Weight.from(Target.Max) is Weight(1)
    (Weight(0) < Weight(1)) is true
    Weight(1) + Weight(1) is Weight(2)
    Weight(2) * 3 is Weight(6)
  }

  it should "check order" in {
    val target0 = Target.unsafe(Target.maxBigInt.shiftRight(1))
    val target1 = Target.unsafe(Target.maxBigInt.shiftRight(2))
    target0 > target1 is true

    val hashrate0 = HashRate.from(target0, Duration.ofSecondsUnsafe(1))
    val hashrate1 = HashRate.from(target1, Duration.ofSecondsUnsafe(1))
    hashrate0 < hashrate1 is true

    val weight0 = Weight.from(target0)
    val weight1 = Weight.from(target1)
    weight0 < weight1 is true
  }
}
