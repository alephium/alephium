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

import org.alephium.serde.Serde

final class StagingSMT[K: Serde, V: Serde](val underlying: CachedSMT[K, V],
                                           val caches: mutable.Map[K, Modified[V]])
    extends CachedTrie[K, V, Modified[V]] {
  protected def getOptFromUnderlying(key: K): IOResult[Option[V]] = underlying.getOpt(key)

  def rollback(): CachedSMT[K, V] = underlying

  def commit(): CachedSMT[K, V] = {
    caches.foreach {
      case (key, updated: Updated[V]) =>
        underlying.caches.get(key) match {
          case Some(_: Inserted[V]) => underlying.caches += key -> Inserted(updated.value)
          case _                    => underlying.caches += key -> updated
        }
      case (key, inserted: Inserted[V]) =>
        underlying.caches.get(key) match {
          case Some(_: Removed[V]) => underlying.caches += key -> Updated(inserted.value)
          case _                   => underlying.caches += key -> inserted
        }
      case (key, removed: Removed[V]) =>
        underlying.caches.get(key) match {
          case Some(_: Inserted[V]) => underlying.caches -= key
          case _                    => underlying.caches += key -> removed
        }
    }
    underlying
  }
}
