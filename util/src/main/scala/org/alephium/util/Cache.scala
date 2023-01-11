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

import java.util.{LinkedHashMap, Map}

object Cache {
  def lru[K, V](maxCapacity: Int): Cache[K, V] = {
    Cache.threadUnsafe(maxCapacity, accessOrder = true)
  }

  def fifo[K, V](maxCapacity: Int): Cache[K, V] = {
    Cache.threadUnsafe(maxCapacity, accessOrder = false)
  }

  def lruSafe[K, V](maxCapacity: Int): Cache[K, V] = {
    Cache.threadSafe(maxCapacity, accessOrder = true)
  }

  def fifoSafe[K, V](maxCapacity: Int): Cache[K, V] = {
    Cache.threadSafe(maxCapacity, accessOrder = false)
  }

  def fifo[K, V](
      maxCapacity: Int,
      getTimeStamp: V => TimeStamp,
      expiryDuration: Duration
  ): Cache[K, V] = {
    Cache.threadUnsafe(maxCapacity, getTimeStamp, expiryDuration, accessOrder = false)
  }

  def fifo[K, V](
      removal: (LinkedHashMap[K, V], Map.Entry[K, V]) => Unit
  ): Cache[K, V] = {
    threadUnsafe(removal, accessOrder = false)
  }

  private def threadUnsafe[K, V](maxCapacity: Int, accessOrder: Boolean): Cache[K, V] = {
    Cache.threadUnsafe[K, V](
      (map: LinkedHashMap[K, V], eldest: Map.Entry[K, V]) =>
        if (map.size() > maxCapacity) { map.remove(eldest.getKey()); () },
      accessOrder
    )
  }

  private def threadSafe[K, V](maxCapacity: Int, accessOrder: Boolean): Cache[K, V] = {
    Cache.threadSafe[K, V](
      (map: LinkedHashMap[K, V], eldest: Map.Entry[K, V]) =>
        if (map.size() > maxCapacity) { map.remove(eldest.getKey()); () },
      accessOrder
    )
  }

  private def threadUnsafe[K, V](
      maxCapacity: Int,
      getTimeStamp: V => TimeStamp,
      expiryDuration: Duration,
      accessOrder: Boolean
  ): Cache[K, V] = new Cache[K, V] with NoLock {
    val m = new Inner[K, V](
      removeEldest = (map, eldest) => {
        if (map.size > maxCapacity) {
          map.remove(eldest.getKey())
        }

        val threshold = TimeStamp.now().minusUnsafe(expiryDuration)
        var continue  = true
        val iterator  = map.entrySet().iterator()
        while (continue && iterator.hasNext) {
          val entry = iterator.next()
          if (getTimeStamp(entry.getValue()) <= threshold) {
            iterator.remove()
          } else {
            continue = false
          }
        }
      },
      32,
      0.75f,
      accessOrder
    )
  }

  private def threadUnsafe[K, V](
      removal: (LinkedHashMap[K, V], Map.Entry[K, V]) => Unit,
      accessOrder: Boolean
  ): Cache[K, V] = {
    new Cache[K, V] with NoLock {
      val m = new Inner(removal, 32, 0.75f, accessOrder)
    }
  }

  private def threadSafe[K, V](
      removal: (LinkedHashMap[K, V], Map.Entry[K, V]) => Unit,
      accessOrder: Boolean
  ): Cache[K, V] = {
    new Cache[K, V] with RWLock {
      val m = new Inner(removal, 32, 0.75f, accessOrder)
    }
  }

  class Inner[K, V](
      removeEldest: (LinkedHashMap[K, V], Map.Entry[K, V]) => Unit,
      initialCapacity: Int,
      loadFactor: Float,
      accessOrder: Boolean
  ) extends LinkedHashMap[K, V](initialCapacity, loadFactor, accessOrder) {
    override protected def removeEldestEntry(eldest: Map.Entry[K, V]): Boolean = {
      removeEldest(this, eldest)
      false // always return false since we remove elements manually
    }
  }
}

trait Cache[K, V] extends SimpleMap[K, V] with Lock {
  def m: Cache.Inner[K, V]

  protected def underlying: Map[K, V] = m

  def contains(key: K): Boolean = readOnly {
    m.containsKey(key)
  }

  def unsafe(key: K): V = readOnly {
    m.get(key)
  }

  def get(key: K): Option[V] = readOnly {
    Option(m.get(key))
  }

  def put(key: K, value: V): Unit = writeOnly {
    m.put(key, value)
    ()
  }

  def remove(key: K): Option[V] = writeOnly {
    Option(m.remove(key))
  }

  def removeIf(p: (K, V) => Boolean): Unit = writeOnly {
    m.entrySet().removeIf(entry => p(entry.getKey, entry.getValue))
    ()
  }

  def clear(): Unit = writeOnly {
    m.clear()
  }
}
