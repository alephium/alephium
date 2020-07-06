package org.alephium.util

import java.util.concurrent.ConcurrentLinkedDeque

object ConcurrentQueue {
  def empty[E]: ConcurrentQueue[E] = {
    val q = new ConcurrentLinkedDeque[E]()
    new ConcurrentQueue[E](q)
  }
}

class ConcurrentQueue[E] private (q: ConcurrentLinkedDeque[E]) {
  def enqueue(elem: E): Unit = {
    q.add(elem)
    ()
  }

  def dequeue: E = {
    val res = q.poll()
    assert(res != null)
    res
  }

  def length: Int = q.size()

  def isEmpty: Boolean = length == 0
}
