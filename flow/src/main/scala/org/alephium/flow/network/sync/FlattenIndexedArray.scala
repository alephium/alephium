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

package org.alephium.flow.network.sync

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.ChainIndex

final private[network] class FlattenIndexedArray[T] private (val array: Array[Option[T]]) {
  @inline def apply(chainIndex: ChainIndex)(implicit groupConfig: GroupConfig): Option[T] =
    array(chainIndex.flattenIndex)

  @inline def reset(): Unit = array.indices.foreach(index => array(index) = None)

  @inline def update(chainIndex: ChainIndex, value: T)(implicit groupConfig: GroupConfig): Unit =
    update(chainIndex, Some(value))

  @inline def update(chainIndex: ChainIndex, value: Option[T])(implicit
      groupConfig: GroupConfig
  ): Unit =
    array(chainIndex.flattenIndex) = value

  def foreach(func: T => Unit): Unit = array.foreach(_.foreach(func))

  def forall(func: T => Boolean): Boolean = array.forall(_.forall(func))

  def exists(func: T => Boolean): Boolean = array.exists(_.exists(func))

  def isEmpty: Boolean  = array.forall(_.isEmpty)
  def nonEmpty: Boolean = !isEmpty
}

object FlattenIndexedArray {
  def empty[T](implicit groupConfig: GroupConfig): FlattenIndexedArray[T] = {
    new FlattenIndexedArray(Array.fill(groupConfig.chainNum)(None))
  }
}
