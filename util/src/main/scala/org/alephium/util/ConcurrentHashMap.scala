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

import java.util.Map
import java.util.concurrent.{ConcurrentHashMap => JCHashMap}

object ConcurrentHashMap {
  def empty[K, V]: ConcurrentHashMap[K, V] = {
    val m = new JCHashMap[K, V]()
    new ConcurrentHashMap[K, V](m)
  }
}

class ConcurrentHashMap[K, V] private (m: JCHashMap[K, V]) extends SimpleMap[K, V] {
  protected def underlying: Map[K, V] = m

  def getUnsafe(k: K): V = {
    val v = m.get(k)
    assume(v != null)
    v
  }

  def get(k: K): Option[V] = {
    Option(m.get(k))
  }

  def contains(k: K): Boolean = m.containsKey(k)

  def put(k: K, v: V): Unit = {
    m.put(k, v)
    ()
  }

  def remove(k: K): Option[V] = {
    Option(m.remove(k))
  }

  def unsafe(key: K): V = ???
}
