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

import java.util.{Comparator, HashMap, Map, TreeMap}

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

object ValueSortedMap {
  def empty[K: Ordering: ClassTag, V: Ordering: ClassTag]: ValueSortedMap[K, V] = {
    val map = new HashMap[K, V]()
    val comparator = new Comparator[K] {
      override def compare(k1: K, k2: K): Int = {
        val c0 = implicitly[Ordering[V]].compare(map.get(k1), map.get(k2))
        if (c0 != 0) {
          c0
        } else {
          implicitly[Ordering[K]].compare(k1, k2)
        }
      }
    }
    val orderedMap = new TreeMap[K, V](comparator)
    new ValueSortedMap[K, V](map, orderedMap)
  }
}

class ValueSortedMap[K: ClassTag, V: ClassTag](
    val map: HashMap[K, V],
    val orderedMap: TreeMap[K, V]
) extends SimpleMap[K, V] {
  protected def underlying: Map[K, V] = orderedMap

  def contains(key: K): Boolean = map.containsKey(key)

  def min: K = orderedMap.firstKey()

  def max: K = orderedMap.lastKey()

  def getMaxValues(n: Int): AVector[V] = {
    AVector.from(orderedMap.descendingMap().values().iterator().asScala.take(n))
  }

  def getMinValues(n: Int): AVector[V] = {
    AVector.from(orderedMap.values().iterator().asScala.take(n))
  }

  def getMaxKeys(n: Int): AVector[K] = {
    AVector.from(orderedMap.descendingKeySet().iterator().asScala.take(n))
  }

  def getMinKeys(n: Int): AVector[K] = {
    AVector.from(orderedMap.navigableKeySet().iterator().asScala.take(n))
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def getAll(): AVector[V] = {
    AVector.unsafe(orderedMap.values.toArray.asInstanceOf[Array[V]])
  }

  def put(key: K, value: V): Unit = {
    map.put(key, value)
    orderedMap.put(key, value)
    ()
  }

  def remove(elem: K): Option[V] = {
    if (map.containsKey(elem)) {
      orderedMap.remove(elem)
      Option(map.remove(elem))
    } else {
      None
    }
  }

  def get(key: K): Option[V] = Option(map.get(key))

  def unsafe(key: K): V = map.get(key)

  def clear(): Unit = {
    orderedMap.clear()
    map.clear()
  }
}
