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

package org.alephium.protocol.vm

import scala.collection.mutable

import org.alephium.io._
import org.alephium.protocol.Hash

final class CachedLogCounterState(
    val underlying: KeyValueStorage[Hash, Int],
    val caches: mutable.Map[Hash, Cache[Int]]
) extends CachedKV[Hash, Int, Cache[Int]] {
  protected def getOptFromUnderlying(key: Hash): IOResult[Option[Int]] = {
    CachedKV.getOptFromUnderlying(underlying, caches, key)
  }

  def persist(): IOResult[KeyValueStorage[Hash, Int]] = {
    CachedKV.persist(underlying, caches)
  }

  def staging(): StagingLogCounterState = new StagingLogCounterState(this, mutable.Map.empty)
}

object CachedLogCounterState {
  def from(storage: KeyValueStorage[Hash, Int]): CachedLogCounterState = {
    new CachedLogCounterState(storage, mutable.Map.empty)
  }
}
