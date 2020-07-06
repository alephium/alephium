package org.alephium.util

import java.util.Map.Entry
import java.util.concurrent.{ConcurrentHashMap => JCHashMap}

import scala.jdk.CollectionConverters._

object ConcurrentHashMap {
  def empty[K, V]: ConcurrentHashMap[K, V] = {
    val m = new JCHashMap[K, V]()
    new ConcurrentHashMap[K, V](m)
  }
}

class ConcurrentHashMap[K, V] private (m: JCHashMap[K, V]) {
  def size: Int = m.size()

  def getUnsafe(k: K): V = {
    val v = m.get(k)
    assert(v != null)
    v
  }

  def get(k: K): Option[V] = {
    Option(m.get(k))
  }

  def contains(k: K): Boolean = m.containsKey(k)

  def add(k: K, v: V): Unit = {
    m.put(k, v)
    ()
  }

  def remove(k: K): Unit = {
    m.remove(k)
    ()
  }

  def keys: Iterable[K] = m.keySet().asScala

  def values: Iterable[V] = m.values().asScala

  def entries: Iterable[Entry[K, V]] = m.entrySet().asScala
}
