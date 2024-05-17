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
    new SizedLruCache[K, V](maxByteSize, getEntrySize) with MutexLock {}
  }

  def threadUnsafe[K, V](maxByteSize: Int, getEntrySize: (K, V) => Int): SizedLruCache[K, V] = {
    new SizedLruCache[K, V](maxByteSize, getEntrySize) with NoLock {}
  }

  final class Inner[K, V](
      initialCapacity: Int,
      loadFactor: Float,
      resize: (util.LinkedHashMap[K, V], util.Map.Entry[K, V]) => Unit
  ) extends util.LinkedHashMap[K, V](initialCapacity, loadFactor, true) {
    override protected def removeEldestEntry(eldest: util.Map.Entry[K, V]): Boolean = {
      resize(this, eldest)
      false
    }
  }
}

abstract class SizedLruCache[K, V](var maxByteSize: Int, getEntrySize: (K, V) => Int)
    extends SimpleMap[K, V]
    with Lock {
  private var _currentByteSize: Int      = 0
  @inline private def overSized: Boolean = _currentByteSize > maxByteSize

  // scalastyle:off magic.number
  private val m =
    new SizedLruCache.Inner[K, V](
      1024,
      0.75f,
      (map, eldest) => {
        if (overSized) {
          val key = eldest.getKey
          map.remove(key)
          _currentByteSize -= getEntrySize(key, eldest.getValue)
          if (overSized) {
            val iterator = map.entrySet().iterator()
            do {
              val entry = iterator.next()
              iterator.remove()
              _currentByteSize -= getEntrySize(entry.getKey, entry.getValue)
            } while (overSized)
          }
        }
      }
    )
  protected def underlying: util.Map[K, V] = m

  def currentByteSize: Int = _currentByteSize

  def setMaxByteSize(newMaxByteSize: Int): Unit = writeOnly {
    maxByteSize = newMaxByteSize
  }

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
