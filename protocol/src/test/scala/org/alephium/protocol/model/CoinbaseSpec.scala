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

import scala.util.Random

import org.alephium.protocol.Generators
import org.alephium.protocol.config.{GroupConfigFixture}
import org.alephium.util.{AlephiumSpec, AVector, U256}

class CoinbaseSpec extends AlephiumSpec with Generators with GroupConfigFixture.Default {
  def checkEqual(u256: U256, double: Double) = {
    val a = BigDecimal(u256.v)
    val b = BigDecimal.valueOf(double)
    ((a - b) / b < 0.01) is true
    ((b - a) / b < 0.01) is true
  }
  it should "check the ghost reward formula" in {
    val double = Random.nextDouble() * Math.pow(10.0, 18) * 3
    val u256   = U256.unsafe(BigDecimal.valueOf(double).toBigInt)
    checkEqual(
      Coinbase.calcMainChainReward(u256),
      double / (0.05 * 7 / 8 * (1 + 1 / 32) + 1)
    )
    (1 to 7) foreach { heightDiff =>
      checkEqual(
        Coinbase.calcGhostUncleReward(u256, heightDiff),
        double * (8 - heightDiff) / 8
      )
    }
    (1 to 3) foreach { uncleNum =>
      checkEqual(
        Coinbase.calcBlockReward(u256, AVector.fill(uncleNum)(u256)),
        double + double * uncleNum / 32
      )
    }
  }
}
