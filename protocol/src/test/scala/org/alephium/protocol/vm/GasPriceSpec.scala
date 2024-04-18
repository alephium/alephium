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

package org.alephium.protocol.vm

import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{coinbaseGasPrice, nonCoinbaseMinGasPrice, HardFork}
import org.alephium.util.{AlephiumSpec, AVector, NumericHelpers}

class GasPriceSpec extends AlephiumSpec with NumericHelpers {
  it should "validate gas price bounds deprecated" in {
    val (isCoinbase, hardfork) =
      AVector(
        true  -> HardFork.Mainnet,
        true  -> HardFork.SinceLemanForTest,
        false -> HardFork.Mainnet
      ).sample()
    GasPrice.validate(coinbaseGasPrice, isCoinbase, hardfork) is true
    GasPrice.validate(
      GasPrice(coinbaseGasPrice.value - 1),
      isCoinbase,
      hardfork
    ) is false
    GasPrice.validate(GasPrice(ALPH.MaxALPHValue), isCoinbase, hardfork) is false
    GasPrice.validate(GasPrice(ALPH.MaxALPHValue - 1), isCoinbase, hardfork) is true
  }

  it should "validate gas price bounds for non-coinbase + Leman fork" in {
    GasPrice.validate(coinbaseGasPrice, isCoinbase = false, HardFork.SinceLemanForTest) is false
    GasPrice.validate(
      nonCoinbaseMinGasPrice,
      isCoinbase = false,
      HardFork.SinceLemanForTest
    ) is true
    GasPrice.validate(
      GasPrice(nonCoinbaseMinGasPrice.value - 1),
      isCoinbase = false,
      HardFork.SinceLemanForTest
    ) is false
    GasPrice.validate(
      GasPrice(ALPH.MaxALPHValue),
      isCoinbase = false,
      HardFork.SinceLemanForTest
    ) is false
    GasPrice.validate(
      GasPrice(ALPH.MaxALPHValue - 1),
      isCoinbase = false,
      HardFork.SinceLemanForTest
    ) is true
  }
}
