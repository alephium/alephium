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

package org.alephium.io

import scala.collection.mutable

final class CachedKVStorage[K, V](
    val underlying: KeyValueStorage[K, V],
    val caches: mutable.HashMap[K, Cache[V]]
) extends CachedKV[K, V, Cache[V]] {
  protected def getOptFromUnderlying(key: K): IOResult[Option[V]] = {
    CachedKV.getOptFromUnderlying(underlying, caches, key)
  }

  def persist(): IOResult[KeyValueStorage[K, V]] = {
    underlying.putBatch(CachedKVStorage.accumulateUpdates(_, caches)).map(_ => underlying)
  }

  def staging(): StagingKVStorage[K, V] = new StagingKVStorage(this, mutable.HashMap.empty)
}

object CachedKVStorage {
  def from[K, V](storage: KeyValueStorage[K, V]): CachedKVStorage[K, V] = {
    new CachedKVStorage[K, V](storage, mutable.HashMap.empty)
  }

  @inline private[io] def accumulateUpdates[K, V](
      putAccumulate: (K, V) => Unit,
      caches: mutable.HashMap[K, Cache[V]]
  ): Unit = {
    caches.foreach {
      case (_, Cached(_))         => Right(())
      case (key, Updated(value))  => putAccumulate(key, value)
      case (key, Inserted(value)) => putAccumulate(key, value)
      case (_, Removed()) =>
        throw new RuntimeException("Unexpected `Remove` action")
    }
  }
}
