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

import scala.collection.immutable.ArraySeq

class CollectionSpec extends AlephiumSpec {
  it should "get element safely" in {
    forAll { array: Array[Int] =>
      Collection.get(array, -1) is None
      Collection.get(array, array.length) is None
      if (array.nonEmpty) {
        Collection.get(array, 0) is Some(array(0))
        Collection.get(array, array.length - 1) is Some(array(array.length - 1))
      }

      val arraySeq = ArraySeq.from(array)
      Collection.get(arraySeq, -1) is None
      Collection.get(arraySeq, arraySeq.length) is None
      if (arraySeq.nonEmpty) {
        Collection.get(arraySeq, 0) is Some(arraySeq(0))
        Collection.get(arraySeq, arraySeq.length - 1) is Some(arraySeq(arraySeq.length - 1))
      }
    }
  }
}
