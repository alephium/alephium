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

package org.alephium.api.model

import org.alephium.protocol.ALPH._
import org.alephium.util._

class AmountSpec extends AlephiumSpec with NumericHelpers {

  it should "parse `x.y ALPH` format" in new Fixture {
    check("1.2ALPH", alph(12) / 10)
    check("1.2 ALPH", alph(12) / 10)
    check("1 ALPH", alph(1))
    check("1ALPH", alph(1))
    check("0.1ALPH", alph(1) / 10)
    check(".1ALPH", alph(1) / 10)
    check(".1     ALPH", alph(1) / 10)
    check("0 ALPH", U256.Zero)
    check("1234.123456 ALPH", alph(1234123456) / 1000000)

    val alphMax = s"${MaxALPHValue.divUnsafe(oneAlph)}"
    alphMax is "1000000000"
    check(s"$alphMax ALPH", MaxALPHValue)

    fail("1.2alph")
    fail("-1.2alph")
    fail("1.2 alph")
    fail("1 Alph")
    fail("1. ALPH")
    fail(". ALPH")
    fail(" ALPH")
    fail("0.000000000000000000001 ALPH")
  }

  trait Fixture {

    def check(str: String, expected: U256) = {
      Amount.from(str) is Some(Amount(expected))
    }
    def fail(str: String) = {
      Amount.from(str) is None
    }
  }
}
