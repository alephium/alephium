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

import java.util.{Comparator, TreeMap}

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

object ValueSortedMap {
  def empty[K: ClassTag, V: Ordering: ClassTag]: ValueSortedMap[K, V] = {
    val map = mutable.HashMap.empty[K, V]
    val comparator = new Comparator[K] {
      override def compare(k1: K, k2: K): Int =
        implicitly[Ordering[V]].compare(map(k1), map(k2))
    }
    val orderedMap = new TreeMap[K, V](comparator)
    new ValueSortedMap[K, V](map, orderedMap)
  }
}

class ValueSortedMap[K: ClassTag, V: ClassTag](
    val map: mutable.HashMap[K, V],
    val orderedMap: TreeMap[K, V]
) {
  def size: Int = map.size

  def isEmpty: Boolean = size == 0

  def contains(key: K): Boolean = map.contains(key)

  def min: K = orderedMap.firstKey()

  def max: K = orderedMap.lastKey()

  def getMaxValues(n: Int): AVector[V] = {
    AVector.fromIterator(orderedMap.descendingMap().values().iterator().asScala.take(n))
  }

  def getMinValues(n: Int): AVector[V] = {
    AVector.fromIterator(orderedMap.values().iterator().asScala.take(n))
  }

  def getMaxKeys(n: Int): AVector[K] = {
    AVector.fromIterator(orderedMap.descendingKeySet().iterator().asScala.take(n))
  }

  def getMinKeys(n: Int): AVector[K] = {
    AVector.fromIterator(orderedMap.navigableKeySet().iterator().asScala.take(n))
  }

  def getAll(): AVector[V] = {
    AVector.unsafe(orderedMap.values.toArray.asInstanceOf[Array[V]])
  }

  def put(key: K, value: V): ValueSortedMap.this.type = {
    map.put(key, value)
    orderedMap.put(key, value)
    this
  }

  def remove(elem: K): ValueSortedMap.this.type = {
    orderedMap.remove(elem)
    map.remove(elem)
    this
  }

  def get(key: K): Option[V] = map.get(key)

  def apply(key: K): V = map(key)

  def clear(): Unit = {
    orderedMap.clear()
    map.clear()
  }
}
