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

import org.alephium.util.discard

abstract class CachedTrie[K, V, C >: Modified[V] <: Cache[V]] extends MutableTrie[K, V] {
  def underlying: ReadableTrie[K, V]

  def caches: mutable.Map[K, C]

  def get(key: K): IOResult[V] = {
    getOpt(key).flatMap {
      case None        => Left(IOError.KeyNotFound(key))
      case Some(value) => Right(value)
    }
  }

  def getOpt(key: K): IOResult[Option[V]] = {
    (caches.get(key): @unchecked) match {
      case None                    => getOptFromUnderlying(key)
      case Some(t: ValueExists[V]) => Right(Some(t.value))
      case Some(Removed())         => Right(None)
    }
  }

  protected def getOptFromUnderlying(key: K): IOResult[Option[V]]

  // we don't cache this function as it is usually used for removal
  def exist(key: K): IOResult[Boolean] = {
    (caches.get(key): @unchecked) match {
      case None                    => underlying.exist(key)
      case Some(_: ValueExists[V]) => Right(true)
      case Some(Removed())         => Right(false)
    }
  }

  def remove(key: K): IOResult[Unit] = {
    (caches.get(key): @unchecked) match {
      case None              => removeForUnderlying(key)
      case Some(Inserted(_)) => Right(discard(caches.subtractOne(key)))
      case Some(Removed())   => Left(IOError.KeyNotFound(key))
      case Some(_)           => Right(discard(caches.addOne(key -> Removed())))
    }
  }

  protected def removeForUnderlying(key: K): IOResult[Unit] = {
    underlying.exist(key).flatMap {
      case true  => Right(discard(caches.addOne(key -> Removed())))
      case false => Left(IOError.KeyNotFound(key))
    }
  }

  def put(key: K, value: V): IOResult[Unit] = {
    (caches.get(key): @unchecked) match {
      case None                            => putForUnderlying(key, value)
      case Some(_: KeyExistedInUnderlying) => Right(discard(caches.addOne(key -> Updated(value))))
      case Some(Inserted(_))               => Right(discard(caches.addOne(key -> Inserted(value))))
    }
  }

  protected def putForUnderlying(key: K, value: V): IOResult[Unit] = {
    underlying.exist(key).map {
      case true  => discard(caches.addOne(key -> Updated(value)))
      case false => discard(caches.addOne(key -> Inserted(value)))
    }
  }
}
