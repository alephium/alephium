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

class SizedLruCacheSpec extends AlephiumSpec {
  it should "work as map" in {
    for (safety <- Seq(true, false)) {
      val cache = if (safety) {
        SizedLruCache.threadSafe[Char, Int](100, (_, v) => v)
      } else {
        SizedLruCache.threadUnsafe[Char, Int](100, (_, v) => v)
      }
      cache.put('a', 0)
      cache.contains('a') is true
      cache.unsafe('a') is 0
      cache.get('a') is Some(0)
      cache.put('b', 1)
      cache.keys().toSeq is Seq('a', 'b')
      cache.values().toSeq is Seq(0, 1)
      cache.put('a', 2)
      cache.keys().toSeq is Seq('b', 'a')
      cache.values().toSeq is Seq(1, 2)
      cache.remove('b')
      cache.remove('a')
      cache.isEmpty is true
    }
  }

  it should "remove elements when there is no capacity" in {
    for (safety <- Seq(true, false)) {
      val cache = if (safety) {
        SizedLruCache.threadSafe[Char, Int](4, (_, v) => v)
      } else {
        SizedLruCache.threadUnsafe[Char, Int](4, (_, v) => v)
      }
      cache.put('a', 1)
      cache.put('b', 2)
      cache.size is 2
      cache.currentByteSize is 3
      cache.get('a') is Some(1)

      cache.put('c', 3)
      cache.keys().toSeq is Seq('a', 'c')
      cache.values().toSeq is Seq(1, 3)
      cache.size is 2
      cache.currentByteSize is 4

      cache.put('d', 4)
      cache.keys().toSeq is Seq('d')
      cache.values().toSeq is Seq(4)
      cache.size is 1
      cache.currentByteSize is 4

      cache.put('3', 5)
      cache.keys().toSeq.isEmpty is true
      cache.values().toSeq.isEmpty is true
      cache.size is 0
      cache.currentByteSize is 0
    }
  }

  it should "change the max size" in {
    val cache = SizedLruCache.threadSafe[Char, Int](4, (_, v) => v)
    cache.put('a', 1)
    cache.put('b', 2)
    cache.put('c', 3)
    cache.size is 1
    cache.currentByteSize is 3
    cache.maxByteSize is 4
    cache.keys().toSeq is Seq('c')

    cache.setMaxByteSize(10)
    cache.put('a', 1)
    cache.put('b', 2)
    cache.size is 3
    cache.currentByteSize is 6
    cache.maxByteSize is 10
    cache.keys().toSeq is Seq('c', 'a', 'b')
  }

  it should "clear all elements" in {
    val cache = SizedLruCache.threadSafe[Int, Unit](10, (k, _) => k)
    (1 until 4).foreach(v => cache.put(v, ()))
    cache.size is 3
    cache.currentByteSize is 6
    cache.keys().toSeq is Seq(1, 2, 3)
    cache.clear()
    cache.size is 0
    cache.currentByteSize is 0
  }
}
