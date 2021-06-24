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

class ValueSortedMapSpec extends AlephiumSpec {
  it should "work as map" in {
    val map = ValueSortedMap.empty[Char, Int]
    map.put('a', 2)
    map.put('b', 3)
    map.put('c', 1)

    map.unsafe('a') is 2
    map.unsafe('b') is 3
    map.unsafe('c') is 1

    map.min is 'c'
    map.max is 'b'

    map.getMaxValues(2) is AVector(3, 2)
    map.getMinValues(2) is AVector(1, 2)
    map.getMaxKeys(2) is AVector('b', 'a')
    map.getMinKeys(2) is AVector('c', 'a')
    map.getAll().toSet is Set(1, 2, 3)

    map.remove('a')
    map.get('a') is None
    map.getAll().toSet is Set(1, 3)

    map.clear()
    map.isEmpty is true
  }

  it should "handle same value" in {
    val map = ValueSortedMap.empty[Char, Int]
    map.put('a', 2)
    map.put('b', 2)
    map.min is 'a'
    map.max is 'b'
  }
}
