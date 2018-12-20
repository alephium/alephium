package org.alephium.util

import java.util.concurrent.CopyOnWriteArraySet

import scala.collection.JavaConverters._

object CopyOnWriteSet {
  def empty[K]: CopyOnWriteSet[K] = {
    val s = new CopyOnWriteArraySet[K]()
    new CopyOnWriteSet[K](s)
  }
}

// Only suitable for small sets
class CopyOnWriteSet[K](s: CopyOnWriteArraySet[K]) {
  def contains(k: K): Boolean = s.contains(k)

  def add(k: K): Unit = {
    s.add(k)
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

  def iterator: Iterator[K] = s.iterator().asScala
}
