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

import scala.jdk.CollectionConverters._

trait SimpleMap[K, V] {
  protected def underlying: Map[K, V]

  def size: Int = underlying.size()

  def isEmpty: Boolean = underlying.isEmpty

  def nonEmpty: Boolean = !isEmpty

  def contains(key: K): Boolean

  def unsafe(key: K): V

  def get(key: K): Option[V]

  def put(key: K, value: V): Unit

  def remove(key: K): Option[V]

  def keys(): Iterator[K] = underlying.keySet().iterator().asScala

  def values(): Iterator[V] = underlying.values().iterator().asScala

  def entries(): Iterator[Map.Entry[K, V]] = underlying.entrySet().iterator().asScala
}
