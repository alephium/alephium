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
import org.alephium.util.EitherF

final class CachedSMT[K: Serde, V: Serde](val underlying: SparseMerkleTrie[K, V],
                                          val caches: mutable.Map[K, Cache[V]])
    extends CachedTrie[K, V, Cache[V]] {
  protected def getOptFromUnderlying(key: K): IOResult[Option[V]] = {
    underlying.getOpt(key).map { valueOpt =>
      valueOpt.foreach(value => caches.addOne(key -> Cached(value)))
      valueOpt
    }
  }

  def persist(): IOResult[SparseMerkleTrie[K, V]] = {
    EitherF.foldTry(caches, underlying) {
      case (trie, (_, Cached(_)))         => Right(trie)
      case (trie, (key, Updated(value)))  => trie.put(key, value)
      case (trie, (key, Inserted(value))) => trie.put(key, value)
      case (trie, (key, Removed()))       => trie.remove(key)
    }
  }

  def staging(): StagingSMT[K, V] = new StagingSMT[K, V](this, mutable.Map.empty)
}

object CachedSMT {
  def from[K: Serde, V: Serde](trie: SparseMerkleTrie[K, V]): CachedSMT[K, V] = {
    new CachedSMT(trie, mutable.Map.empty)
  }
}
