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

import scala.collection.mutable

import org.alephium.util.{AlephiumSpec, AVector}

class VarVectorSpec extends AlephiumSpec {
  it should "get and set values" in {
    val vector = VarVector.unsafe[Int](mutable.ArraySeq.from(Array.ofDim[Int](10)), 1, 2)
    vector.length is 2
    vector.set(0, 0).rightValue is ()
    vector.set(1, 1).rightValue is ()
    vector.get(0) isE 0
    vector.get(1) isE 1
    vector.set(-1, -1).leftValue isE InvalidVarIndex(-1, 1)
    vector.get(-1).leftValue isE InvalidVarIndex(-1, 1)
    vector.set(2, 2).leftValue isE InvalidVarIndex(2, 1)
    vector.get(2).leftValue isE InvalidVarIndex(2, 1)
    vector.sameElements(AVector(0)) is false
    vector.sameElements(AVector(0, 1)) is true
    vector.setIf(1, 2, _ => okay).rightValue is ()
    vector.setIf(-1, -1, _ => okay).leftValue isE InvalidVarIndex(-1, 1)
    vector.sameElements(AVector(0)) is false
    vector.sameElements(AVector(0, 1)) is false
    vector.sameElements(AVector(0, 2)) is true
  }
}
