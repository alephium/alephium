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

package org.alephium.protocol

import org.alephium.util.{AlephiumSpec, NumericHelpers, U256}

class ALPHSpec extends AlephiumSpec {
  import ALPH._

  it should "use correct unit" in {
    alph(1) is nanoAlph(1).mul(U256.Billion).get
    alph(1).toBigInt.longValue() is math.pow(10, 18).longValue()
    cent(1).mulUnsafe(U256.unsafe(100)) is alph(1)

    oneAlph is alph(1)
    oneNanoAlph is nanoAlph(1)
    oneAlph is (oneNanoAlph.mulUnsafe(U256.unsafe(1000000000)))
  }

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

  trait Fixture extends NumericHelpers {

    def check(str: String, expected: U256) = {
      ALPH.alphFromString(str) is Some(expected)
    }
    def fail(str: String) = {
      ALPH.alphFromString(str) is None
    }
  }
}
