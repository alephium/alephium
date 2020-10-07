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

import java.util.concurrent.{ConcurrentHashMap => JCHashMap}

import scala.jdk.CollectionConverters._

object ConcurrentHashSet {
  def empty[K]: ConcurrentHashSet[K] = {
    val s = new JCHashMap[K, Boolean]()
    new ConcurrentHashSet[K](s)
  }
}

// Only suitable for small sets
class ConcurrentHashSet[K](s: JCHashMap[K, Boolean]) {
  def size: Int = s.size()

  def contains(k: K): Boolean = s.containsKey(k)

  def add(k: K): Unit = {
    s.put(k, true)
    ()
  }

  def remove(k: K): Unit = {
    val result = s.remove(k)
    assume(result)
  }

  def removeIfExist(k: K): Unit = {
    s.remove(k)
    ()
  }

  def iterable: Iterable[K] = s.synchronized {
    s.keySet().asScala.toIndexedSeq
  }
}
