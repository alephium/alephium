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

import org.alephium.util.{AlephiumSpec, U256}

class ALPHSpec extends AlephiumSpec {
  it should "use correct unit" in {
    ALPH.alph(1) is ALPH.nanoAlph(1).mul(U256.Billion).get
    ALPH.alph(1).toBigInt.longValue() is math.pow(10, 18).longValue()
    ALPH.cent(1).mulUnsafe(U256.unsafe(100)) is ALPH.alph(1)

    ALPH.oneAlph is ALPH.alph(1)
    ALPH.oneNanoAlph is ALPH.nanoAlph(1)
    ALPH.oneAlph is (ALPH.oneNanoAlph.mulUnsafe(U256.unsafe(1000000000)))
  }
}
