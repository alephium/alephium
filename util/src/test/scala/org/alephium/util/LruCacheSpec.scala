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

class LruCacheSpec extends AlephiumSpec {
  it should "work as map" in {
    val buffer = LruCache[Char, Int](100, accessOrder = false)
    buffer.put('a', 0)
    buffer.contains('a') is true
    buffer.unsafe('a') is 0
    buffer.get('a') is Some(0)
    buffer.put('b', 1)
    buffer.keys().toSeq is Seq('a', 'b')
    buffer.values().toSeq is Seq(0, 1)
    buffer.put('a', 2)
    buffer.keys().toSeq is Seq('b', 'a')
    buffer.values().toSeq is Seq(1, 2)
    buffer.remove('b')
    buffer.remove('a')
    buffer.isEmpty is true
  }

  it should "remove elements when there is no capacity" in {
    val buffer = LruCache[Char, Int](1, accessOrder = false)
    buffer.put('a', 0)
    buffer.put('b', 1)
    buffer.size is 1
    buffer.keys().toSeq is Seq('b')
    buffer.values().toSeq is Seq(1)
  }
}
