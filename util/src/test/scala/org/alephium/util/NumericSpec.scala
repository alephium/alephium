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

package org.alephium.util

import java.math.BigInteger

class NumericSpec extends AlephiumSpec {
  it should "check positivity of big integer" in {
    Numeric.isPositive(BigInteger.ONE) is true
    Numeric.nonPositive(BigInteger.ONE) is false
    Numeric.isNegative(BigInteger.ONE) is false
    Numeric.nonNegative(BigInteger.ONE) is true
    Numeric.isPositive(BigInteger.ZERO) is false
    Numeric.nonPositive(BigInteger.ZERO) is true
    Numeric.isNegative(BigInteger.ZERO) is false
    Numeric.nonNegative(BigInteger.ZERO) is true
    Numeric.isPositive(BigInteger.ONE.negate()) is false
    Numeric.nonPositive(BigInteger.ONE.negate()) is true
    Numeric.isNegative(BigInteger.ONE.negate()) is true
    Numeric.nonNegative(BigInteger.ONE.negate()) is false
  }
}
