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

import java.util.Map.Entry
import java.util.concurrent.{ConcurrentHashMap => JCHashMap}

import scala.jdk.CollectionConverters._

object ConcurrentHashMap {
  def empty[K, V]: ConcurrentHashMap[K, V] = {
    val m = new JCHashMap[K, V]()
    new ConcurrentHashMap[K, V](m)
  }
}

class ConcurrentHashMap[K, V] private (m: JCHashMap[K, V]) {
  def size: Int = m.size()

  def getUnsafe(k: K): V = {
    val v = m.get(k)
    assume(v != null)
    v
  }

  def get(k: K): Option[V] = {
    Option(m.get(k))
  }

  def contains(k: K): Boolean = m.containsKey(k)

  def add(k: K, v: V): Unit = {
    m.put(k, v)
    ()
  }

  def remove(k: K): Unit = {
    m.remove(k)
    ()
  }

  def keys: Iterable[K] = m.keySet().asScala.toIndexedSeq

  def values: Iterable[V] = m.values().asScala.toIndexedSeq

  def entries: Iterable[Entry[K, V]] = m.entrySet().asScala
}
