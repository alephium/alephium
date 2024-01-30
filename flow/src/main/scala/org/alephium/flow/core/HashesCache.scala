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

package org.alephium.flow.core

import java.util.TreeMap

import org.alephium.protocol.model.BlockHash
import org.alephium.util.{AVector, RWLock}

object HashesCache {
  def apply(capacity: Int): HashesCache = {
    val underlying = new TreeMap[Int, AVector[BlockHash]]
    new HashesCache(underlying, capacity)
  }
}

final class HashesCache(val underlying: TreeMap[Int, AVector[BlockHash]], val capacity: Int)
    extends RWLock {
  def size: Int = readOnly(underlying.size)

  def exists(key: Int): Boolean = readOnly(underlying.containsKey(key))

  def get(key: Int): Option[AVector[BlockHash]] = readOnly(Option(underlying.get(key)))

  def getE[E](
      key: Int
  )(genValue: => Either[E, AVector[BlockHash]]): Either[E, AVector[BlockHash]] = {
    get(key) match {
      case Some(value) => Right(value)
      case None        => genValue
    }
  }

  def getUnsafe(key: Int)(genValue: => AVector[BlockHash]): AVector[BlockHash] = {
    get(key) match {
      case Some(value) => value
      case None        => genValue
    }
  }

  def put(key: Int, value: AVector[BlockHash]): Unit = writeOnly {
    underlying.put(key, value)
    evict()
  }

  private def evict(): Unit = {
    if (underlying.size > capacity) {
      underlying.remove(underlying.firstKey())
      ()
    }
  }
}
