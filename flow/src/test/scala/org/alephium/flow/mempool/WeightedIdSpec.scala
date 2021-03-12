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

package org.alephium.flow.mempool

import org.scalacheck.Arbitrary.{arbAnyVal, arbitrary}

import org.alephium.flow.mempool.TxPool.WeightedId
import org.alephium.protocol.Generators
import org.alephium.util.{AlephiumSpec, U256}

class WeightedIdSpec extends AlephiumSpec with Generators {
  it should "equals by id" in {
    forAll(hashGen, arbitrary[U256], hashGen, arbitrary[U256], arbAnyVal) {
      (id1, amount1, id2, amount2, any) =>
        WeightedId(amount1, id1) is WeightedId(amount2, id1)
        WeightedId(amount1, id1) isnot WeightedId(amount1, id2)

        WeightedId(amount1, id1).hashCode is WeightedId(amount2, id1).hashCode
        WeightedId(amount1, id1).hashCode isnot WeightedId(amount1, id2).hashCode

        WeightedId(amount1, id1).equals(any) is false
    }
  }
}
