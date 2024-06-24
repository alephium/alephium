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

package org.alephium.flow.gasestimation

import scala.util.Random

import org.alephium.protocol.model._
import org.alephium.protocol.vm.GasBox
import org.alephium.util.AlephiumSpec

class GasEstimationMultiplierSpec extends AlephiumSpec {
  it should "validate gas estimation multiplier" in {
    GasEstimationMultiplier.from(0.9).leftValue is
      "Invalid gas estimation multiplier, expected a value between [1.0, 2.0]"
    GasEstimationMultiplier.from(2.01).leftValue is
      "Invalid gas estimation multiplier, expected a value between [1.0, 2.0]"
    GasEstimationMultiplier.from(1).rightValue.value is 1
    GasEstimationMultiplier.from(1.05).rightValue.value is 1.05
    GasEstimationMultiplier.from(1.5).rightValue.value is 1.5
    GasEstimationMultiplier.from(2).rightValue.value is 2
    GasEstimationMultiplier.from(1.001).leftValue is
      "Invalid gas estimation multiplier precision, maximum allowed precision is 2"
  }

  it should "multiple gas" in {
    (GasEstimationMultiplier.from(2.0).rightValue * maximalGasPerTx).value is 10000000
    (GasEstimationMultiplier.from(1.05).rightValue * maximalGasPerTx).value is 5250000
    (GasEstimationMultiplier.from(1).rightValue * maximalGasPerTx).value is 5000000

    val multiplier = s"1.${Random.nextInt(100)}".toDouble
    val gas0       = GasBox.unsafe(Random.nextInt(maximalGasPerTx.value))
    val gas1       = GasEstimationMultiplier.from(multiplier).rightValue * gas0
    gas1.value is ((multiplier * 100).toInt * gas0.value / 100)
  }
}
