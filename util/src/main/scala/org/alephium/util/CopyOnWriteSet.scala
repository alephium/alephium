package org.alephium.util

import java.util.concurrent.{ConcurrentHashMap => JCHashMap}

import scala.collection.JavaConverters._

object CopyOnWriteSet {
  def empty[K]: CopyOnWriteSet[K] = {
    val s = new JCHashMap[K, Boolean]()
    new CopyOnWriteSet[K](s)
  }
}

// Only suitable for small sets
class CopyOnWriteSet[K](s: JCHashMap[K, Boolean]) {
  def contains(k: K): Boolean = s.containsKey(k)

  def add(k: K): Unit = {
    s.put(k, true)
    ()
  }

  def remove(k: K): Unit = {
    val result = s.remove(k)
    assert(result)
  }

  def removeIfExist(k: K): Unit = {
    s.remove(k)
    ()
  }

  def iterator: Iterator[K] = s.keys().asScala
}
