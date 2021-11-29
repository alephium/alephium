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
import org.alephium.protocol.model.minimalGasPrice
import org.alephium.util.{AlephiumSpec, NumericHelpers}

class GasPriceSpec extends AlephiumSpec with NumericHelpers {
  it should "validate gas price bounds" in {
    GasPrice.validate(minimalGasPrice) is true
    GasPrice.validate(GasPrice(minimalGasPrice.value - 1)) is false
    GasPrice.validate(GasPrice(ALPH.MaxALPHValue)) is false
    GasPrice.validate(GasPrice(ALPH.MaxALPHValue - 1)) is true
  }
}
