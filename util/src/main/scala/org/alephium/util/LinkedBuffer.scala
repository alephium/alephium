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

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object LinkedBuffer {
  def apply[K, V](maxCapacity: Int): LinkedBuffer[K, V] = {
    val m = new Inner[K, V](maxCapacity, 32, 0.75f)
    new LinkedBuffer[K, V](m)
  }

  private class Inner[K, V](maxCapacity: Int, initialCapacity: Int, loadFactor: Float)
      extends LinkedHashMap[K, V](initialCapacity, loadFactor) {
    override protected def removeEldestEntry(eldest: Map.Entry[K, V]): Boolean = {
      this.size > maxCapacity
    }
  }
}

class LinkedBuffer[K, V](m: LinkedBuffer.Inner[K, V]) extends mutable.Map[K, V] {
  override def get(key: K): Option[V] = Option(m.get(key))

  override def subtractOne(key: K): LinkedBuffer.this.type = {
    m.remove(key)
    this
  }

  override def addOne(elem: (K, V)): LinkedBuffer.this.type = {
    m.remove(elem._1)
    m.put(elem._1, elem._2)
    this
  }

  override def iterator: Iterator[(K, V)] = {
    m.asScala.iterator
  }
}
