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
    assume(res != null)
    res
  }

  def length: Int = q.size()

  def isEmpty: Boolean = length == 0
}
