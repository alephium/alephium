package org.alephium.util

import java.util.{LinkedHashMap, Map}
import java.util.Map.Entry

import scala.jdk.CollectionConverters._

object LruCache {
  def apply[K, V, E](maxCapacity: Int): LruCache[K, V, E] = {
    apply(maxCapacity, 32, 0.75f)
  }

  def apply[K, V, E](maxCapacity: Int,
                     initialCapacity: Int,
                     loadFactor: Float): LruCache[K, V, E] = {
    val m = new Inner[K, V](maxCapacity, initialCapacity, loadFactor)
    new LruCache[K, V, E](m)
  }

  private class Inner[K, V](maxCapacity: Int, initialCapacity: Int, loadFactor: Float)
      extends LinkedHashMap[K, V](initialCapacity, loadFactor) {
    override protected def removeEldestEntry(eldest: Map.Entry[K, V]): Boolean = {
      this.size > maxCapacity
    }
  }
}

class LruCache[K, V, E](m: LruCache.Inner[K, V]) extends RWLock {
  def get(key: K)(genValue: => Either[E, V]): Either[E, V] = {
    getInCache(key) match {
      case Some(value) =>
        refresh(key, value)
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
        refresh(key, value)
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
        refresh(key, value)
        Right(Some(value))
      case None =>
        genValueOpt.map { valueOpt =>
          valueOpt.map(value => { putInCache(key, value); value })
        }
    }
  }

  def getOptUnsafe(key: K)(genValueOpt: => Option[V]): Option[V] = {
    getInCache(key) match {
      case Some(value) =>
        refresh(key, value)
        Some(value)
      case None =>
        genValueOpt.map(value => { putInCache(key, value); value })
    }
  }

  def exists(key: K)(genValue: => Either[E, Boolean]): Either[E, Boolean] = {
    existsInCache(key) match {
      case true  => Right(true)
      case false => genValue
    }
  }

  def existsUnsafe(key: K)(genValue: => Boolean): Boolean = {
    existsInCache(key) || genValue
  }

  private def getInCache(key: K): Option[V] = readOnly {
    Option(m.get(key))
  }

  private def existsInCache(key: K): Boolean = readOnly {
    m.containsKey(key)
  }

  def putInCache(key: K, value: V): Unit = writeOnly {
    m.put(key, value)
    ()
  }

  private def refresh(key: K, value: V): Unit = writeOnly {
    m.remove(key)
    m.put(key, value)
    ()
  }

  def keys: Iterable[K] = m.keySet().asScala

  def values: Iterable[V] = m.values().asScala

  def entries: Iterable[Entry[K, V]] = m.entrySet().asScala
}
