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

import org.alephium.crypto.{Blake2b => Hash}
import org.alephium.util.{AVector, EitherF}

final class CachedSMT[K, V](
    val underlying: SparseMerkleTrie[K, V],
    val caches: mutable.Map[K, Cache[V]]
) extends CachedKV[K, V, Cache[V]] {
  protected def getOptFromUnderlying(key: K): IOResult[Option[V]] = {
    CachedKV.getOptFromUnderlying(underlying, caches, key)
  }

  def persist(): IOResult[SparseMerkleTrie[K, V]] = {
    for {
      inMemoryTrie <- persistInMemory()
      persisted    <- inMemoryTrie.persistInBatch()
    } yield persisted
  }

  private def persistInMemory(): IOResult[InMemorySparseMerkleTrie[K, V]] = {
    val inMemoryTrie = underlying.inMemory()
    for {
      _ <- EitherF.foreachTry(caches) {
        case (_, Cached(_))         => Right(())
        case (key, Updated(value))  => inMemoryTrie.put(key, value)
        case (key, Inserted(value)) => inMemoryTrie.put(key, value)
        case (key, Removed())       => inMemoryTrie.remove(key)
      }
    } yield inMemoryTrie
  }

  def getRootHash(): IOResult[Hash] = persistInMemory().map(_.rootHash)

  def getNewTrieNodeKeys(): IOResult[AVector[Hash]] = {
    persistInMemory().map(_.getNewTrieNodeKeys())
  }

  def staging(): StagingSMT[K, V] = new StagingSMT[K, V](this, mutable.Map.empty)
}

object CachedSMT {
  def from[K, V](trie: SparseMerkleTrie[K, V]): CachedSMT[K, V] = {
    new CachedSMT(trie, mutable.Map.empty)
  }
}
