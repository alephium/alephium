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

class NumberSpec extends AlephiumSpec {
  it should "check positivity of big integer" in {
    Number.isPositive(BigInteger.ONE) is true
    Number.nonPositive(BigInteger.ONE) is false
    Number.isNegative(BigInteger.ONE) is false
    Number.nonNegative(BigInteger.ONE) is true
    Number.isPositive(BigInteger.ZERO) is false
    Number.nonPositive(BigInteger.ZERO) is true
    Number.isNegative(BigInteger.ZERO) is false
    Number.nonNegative(BigInteger.ZERO) is true
    Number.isPositive(BigInteger.ONE.negate()) is false
    Number.nonPositive(BigInteger.ONE.negate()) is true
    Number.isNegative(BigInteger.ONE.negate()) is true
    Number.nonNegative(BigInteger.ONE.negate()) is false
  }

  it should "check constants" in {
    Number.million is math.pow(10, 6).longValue()
    Number.billion is math.pow(10, 9).longValue()
    Number.trillion is math.pow(10, 12).longValue()
    Number.quadrillion is math.pow(10, 15).longValue()
    Number.quintillion is math.pow(10, 18).longValue()
    Number.quintillion is Number.billion * Number.billion
  }
}
