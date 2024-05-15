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

import java.util

object SizedLruCache {
  def threadSafe[K, V](maxByteSize: Int, getEntrySize: (K, V) => Int): SizedLruCache[K, V] = {
    new SizedLruCache[K, V](maxByteSize, getEntrySize) with RWLock {}
  }

  def threadUnsafe[K, V](maxByteSize: Int, getEntrySize: (K, V) => Int): SizedLruCache[K, V] = {
    new SizedLruCache[K, V](maxByteSize, getEntrySize) with NoLock {}
  }

  final class Inner[K, V](
      initialCapacity: Int,
      loadFactor: Float,
      accessOrder: Boolean,
      removeEldest: util.Map.Entry[K, V] => Boolean
  ) extends util.LinkedHashMap[K, V](initialCapacity, loadFactor, accessOrder) {
    override protected def removeEldestEntry(eldest: util.Map.Entry[K, V]): Boolean = {
      removeEldest(eldest)
    }
  }
}

abstract class SizedLruCache[K, V](maxByteSize: Int, getEntrySize: (K, V) => Int)
    extends SimpleMap[K, V]
    with Lock {
  private var _currentByteSize: Int = 0
  // scalastyle:off magic.number
  private val m =
    new SizedLruCache.Inner[K, V](
      1024,
      0.75f,
      true,
      eldest => {
        val result = _currentByteSize > maxByteSize
        if (result) _currentByteSize -= getEntrySize(eldest.getKey, eldest.getValue)
        result
      }
    )
  protected def underlying: util.Map[K, V] = m

  def currentByteSize: Int = _currentByteSize

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
    _currentByteSize += getEntrySize(key, value)
    m.put(key, value)
    ()
  }

  def remove(key: K): Option[V] = writeOnly {
    val result = Option(m.remove(key))
    result.foreach(value => _currentByteSize -= getEntrySize(key, value))
    result
  }

  def clear(): Unit = writeOnly {
    m.clear()
    _currentByteSize = 0
  }
}
