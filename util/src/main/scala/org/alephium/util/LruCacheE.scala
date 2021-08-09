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

import java.util.{LinkedHashMap, Map}
import java.util.Map.Entry

import scala.jdk.CollectionConverters._

object LruCacheE {
  def threadSafe[K, V, E](maxCapacity: Int): LruCacheE[K, V, E] = {
    threadSafe(maxCapacity, 32, 0.75f)
  }

  def threadSafe[K, V, E](
      maxCapacity: Int,
      initialCapacity: Int,
      loadFactor: Float
  ): LruCacheE[K, V, E] = {
    val m = new Inner[K, V](maxCapacity, initialCapacity, loadFactor)
    new ThreadSafeLruCache[K, V, E](m)
  }

  def threadUnsafe[K, V, E](maxCapacity: Int): LruCacheE[K, V, E] = {
    threadUnsafe(maxCapacity, 32, 0.75f)
  }

  def threadUnsafe[K, V, E](
      maxCapacity: Int,
      initialCapacity: Int,
      loadFactor: Float
  ): LruCacheE[K, V, E] = {
    val m = new Inner[K, V](maxCapacity, initialCapacity, loadFactor)
    new ThreadUnsafeLruCache[K, V, E](m)
  }

  class Inner[K, V](maxCapacity: Int, initialCapacity: Int, loadFactor: Float)
      extends LinkedHashMap[K, V](initialCapacity, loadFactor, true) {
    override protected def removeEldestEntry(eldest: Map.Entry[K, V]): Boolean = {
      this.size > maxCapacity
    }
  }
}

trait LruCacheE[K, V, E] extends Lock {
  def m: LruCacheE.Inner[K, V]

  def get(key: K)(genValue: => Either[E, V]): Either[E, V] = {
    getInCache(key) match {
      case Some(value) =>
        Right(value)
      case None =>
        genValue.map { value =>
          putInCache(key, value)
          value
        }
    }
  }

  def getUnsafe(key: K)(genValue: => V): V = {
    getInCache(key) match {
      case Some(value) =>
        value
      case None =>
        val value = genValue
        putInCache(key, value)
        value
    }
  }

  def getOpt(key: K)(genValueOpt: => Either[E, Option[V]]): Either[E, Option[V]] = {
    getInCache(key) match {
      case Some(value) =>
        Right(Some(value))
      case None =>
        genValueOpt.map { valueOpt => valueOpt.map(value => { putInCache(key, value); value }) }
    }
  }

  def getOptUnsafe(key: K)(genValueOpt: => Option[V]): Option[V] = {
    getInCache(key) match {
      case Some(value) =>
        Some(value)
      case None =>
        genValueOpt.map(value => { putInCache(key, value); value })
    }
  }

  def exists(key: K)(genValue: => Either[E, Boolean]): Either[E, Boolean] = {
    if (existsInCache(key)) {
      Right(true)
    } else {
      genValue
    }
  }

  def existsUnsafe(key: K)(genValue: => Boolean): Boolean = {
    existsInCache(key) || genValue
  }

  def getInCache(key: K): Option[V] =
    readOnly {
      Option(m.get(key))
    }

  private def existsInCache(key: K): Boolean =
    readOnly {
      m.containsKey(key)
    }

  def putInCache(key: K, value: V): Unit =
    writeOnly {
      m.put(key, value)
      ()
    }

  def keys: Iterable[K] = m.keySet().asScala

  def values: Iterable[V] = m.values().asScala

  def entries: Iterable[Entry[K, V]] = m.entrySet().asScala
}

class ThreadSafeLruCache[K, V, E](val m: LruCacheE.Inner[K, V])
    extends LruCacheE[K, V, E]
    with RWLock

class ThreadUnsafeLruCache[K, V, E](val m: LruCacheE.Inner[K, V])
    extends LruCacheE[K, V, E]
    with NoLock
