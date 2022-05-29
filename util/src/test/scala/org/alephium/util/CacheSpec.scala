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

class CacheSpec extends AlephiumSpec {
  it should "work as map" in {
    for (accessOrder <- Seq(true, false)) {
      val cache = if (accessOrder) {
        Cache.lru[Char, Int](100)
      } else {
        Cache.fifo[Char, Int](100)
      }
      cache.put('a', 0)
      cache.contains('a') is true
      cache.unsafe('a') is 0
      cache.get('a') is Some(0)
      cache.put('b', 1)
      cache.keys().toSeq is Seq('a', 'b')
      cache.values().toSeq is Seq(0, 1)
      cache.put('a', 2)
      if (accessOrder) {
        cache.keys().toSeq is Seq('b', 'a')
        cache.values().toSeq is Seq(1, 2)
      } else {
        cache.keys().toSeq is Seq('a', 'b')
        cache.values().toSeq is Seq(2, 1)
      }
      cache.remove('b')
      cache.remove('a')
      cache.isEmpty is true
    }
  }

  it should "remove elements when there is no capacity" in {
    for (accessOrder <- Seq(true, false)) {
      val cache = if (accessOrder) {
        Cache.lru[Char, Int](2)
      } else {
        Cache.fifo[Char, Int](2)
      }
      cache.put('a', 0)
      cache.put('b', 1)
      cache.size is 2
      cache.get('a') is Some(0)
      cache.put('c', 2)
      if (accessOrder) {
        cache.keys().toSeq is Seq('a', 'c')
        cache.values().toSeq is Seq(0, 2)
      } else {
        cache.keys().toSeq is Seq('b', 'c')
        cache.values().toSeq is Seq(1, 2)
      }
    }
  }

  it should "remove elements based on capacity and timestamp" in {
    val cache = Cache.fifo[Char, TimeStamp](3, identity[TimeStamp](_), Duration.ofSecondsUnsafe(2))

    cache.put('a', TimeStamp.now())
    cache.put('b', TimeStamp.now())
    cache.put('c', TimeStamp.now())
    cache.size is 3
    cache.keys().toSeq is Seq('a', 'b', 'c')
    Thread.sleep(1000)
    cache.put('d', TimeStamp.now())
    cache.size is 3
    cache.keys().toSeq is Seq('b', 'c', 'd')
    Thread.sleep(1000)
    cache.put('f', TimeStamp.now())
    cache.size is 2
    cache.keys().toSeq is Seq('d', 'f')
  }

  it should "clear all elements" in {
    val cache = Cache.fifo[Int, Unit](10)
    (0 until 3).foreach(v => cache.put(v, ()))
    cache.size is 3
    cache.keys().toSeq is Seq(0, 1, 2)
    cache.clear()
    cache.size is 0
  }
}
