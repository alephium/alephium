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

final class CachedTrie[K: Serde, V: Serde](
    underlying: SparseMerkleTrie[K, V],
    caches: mutable.Map[K, Cache[V]]
) {
  def get(key: K): IOResult[V] = {
    getOpt(key).flatMap {
      case None        => Left(IOError.KeyNotFound(key))
      case Some(value) => Right(value)
    }
  }

  def getOpt(key: K): IOResult[Option[V]] = {
    caches.get(key) match {
      case None                     => getOptFromUnderlying(key)
      case Some(t: ValueExisted[V]) => Right(Some(t.value))
      case Some(Removed())          => Right(None)
    }
  }

  private def getOptFromUnderlying(key: K): IOResult[Option[V]] = {
    underlying.getOpt(key).map { valueOpt =>
      valueOpt.foreach(value => caches.addOne(key -> Cached(value)))
      valueOpt
    }
  }

  def remove(key: K): IOResult[Unit] = {
    caches.get(key) match {
      case None              => removeForUnderlying(key)
      case Some(Cached(_))   => Right(caches.addOne(key -> Removed()))
      case Some(Inserted(_)) => Right(caches.subtractOne(key))
      case Some(Updated(_))  => Right(caches.addOne(key -> Removed()))
      case Some(Removed())   => Left(IOError.KeyNotFound(key))
    }
  }

  private def removeForUnderlying(key: K): IOResult[Unit] = {
    underlying.exist(key).flatMap { existed =>
      if (existed) {
        Right(caches.addOne(key -> Removed()))
      } else {
        Left(IOError.KeyNotFound(key))
      }
    }
  }

  def put(key: K, value: V): IOResult[Unit] = {
    caches.get(key) match {
      case None                    => putForUnderlying(key, value)
      case Some(_: KeyExistedInDB) => Right(caches.addOne(key -> Updated(value)))
      case Some(Inserted(_))       => Right(caches.addOne(key -> Inserted(value)))
    }
  }

  private def putForUnderlying(key: K, value: V): IOResult[Unit] = {
    underlying.exist(key).flatMap { existed =>
      if (existed) {
        Right(caches.addOne(key -> Updated(value)))
      } else {
        Right(caches.addOne(key -> Inserted(value)))
      }
    }
  }

  def persist(): IOResult[SparseMerkleTrie[K, V]] = {
    EitherF.foldTry(caches, underlying) {
      case (trie, (_, Cached(_)))         => Right(trie)
      case (trie, (key, Inserted(value))) => trie.put(key, value)
      case (trie, (key, Removed()))       => trie.remove(key)
      case (trie, (key, Updated(value)))  => trie.put(key, value)
    }
  }
}

object CachedTrie {
  def from[K: Serde, V: Serde](trie: SparseMerkleTrie[K, V]): CachedTrie[K, V] = {
    new CachedTrie(trie, mutable.Map.empty)
  }
}
