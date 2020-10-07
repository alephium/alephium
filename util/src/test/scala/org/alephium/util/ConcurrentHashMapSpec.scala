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

class ConcurrentHashMapSpec extends AlephiumSpec {
  trait Fixture {
    val map = ConcurrentHashMap.empty[Int, Long]
  }

  it should "add / remove /contains" in new Fixture {
    forAll { (k: Int, v: Long) =>
      map.contains(k) is false
      map.add(k, v)
      map.contains(k) is true
      map.remove(k)
      map.contains(k) is false
      map.remove(k)
    }
  }
}
