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

package org.alephium.protocol.vm

import scala.{specialized => sp}
import scala.collection.mutable
import scala.reflect.ClassTag

import org.alephium.util.AVector

object Stack {
  def ofCapacity[T: ClassTag](capacity: Int): Stack[T] = {
    val underlying = mutable.ArraySeq.make(new Array[T](capacity))
    new Stack(underlying, 0, currentIndex = 0, maxIndex = capacity)
  }

  def popOnly[T: ClassTag](elems: AVector[T]): Stack[T] = {
    val underlying = mutable.ArraySeq.make(elems.toArray)
    popOnly(underlying)
  }

  def unsafe[T: ClassTag](elems: AVector[T], maxSize: Int): Stack[T] = {
    assume(elems.length <= maxSize)
    val array = Array.ofDim[T](maxSize)
    elems.foreachWithIndex((t, index) => array(index) = t)
    val underlying = mutable.ArraySeq.make(array)
    new Stack[T](underlying, 0, currentIndex = elems.length, maxIndex = maxSize)
  }

  def popOnly[T: ClassTag](elems: mutable.ArraySeq[T]): Stack[T] = {
    new Stack(elems, 0, currentIndex = elems.length, maxIndex = elems.length)
  }
}

// Note: the element at currentIndex is empty
class Stack[@sp T: ClassTag](
    val underlying: mutable.ArraySeq[T],
    val offset: Int,
    var currentIndex: Int,
    val maxIndex: Int
) {
  def capacity: Int = maxIndex - offset

  def isEmpty: Boolean = currentIndex == offset

  def size: Int = currentIndex - offset

  def top: Option[T] = Option.when(currentIndex >= 1)(underlying(currentIndex - 1))

  def push(elem: T): ExeResult[Unit] = {
    if (currentIndex < maxIndex) {
      underlying(currentIndex) = elem
      currentIndex += 1
      Right(())
    } else {
      failed(StackOverflow)
    }
  }

  def push(elems: AVector[T]): ExeResult[Unit] = {
    if (currentIndex + elems.length <= maxIndex) {
      elems.foreachWithIndex((elem, index) => underlying(currentIndex + index) = elem)
      currentIndex += elems.length
      Right(())
    } else {
      failed(StackOverflow)
    }
  }

  def pop(): ExeResult[T] = {
    val elemIndex = currentIndex - 1
    if (elemIndex >= offset) {
      val elem = underlying(elemIndex)
      currentIndex = elemIndex
      Right(elem)
    } else {
      failed(StackUnderflow)
    }
  }

  def pop(n: Int): ExeResult[AVector[T]] = {
    if (n > size) {
      failed(StackUnderflow)
    } else if (n > 0) {
      val start = currentIndex - n // always >= offset
      val elems = AVector.tabulate(n) { k => underlying(start + k) }
      currentIndex = start
      Right(elems)
    } else if (n == 0) {
      Right(AVector.ofCapacity(0))
    } else {
      failed(NegativeArgumentInStack)
    }
  }

  def swapTopTwo(): ExeResult[Unit] = {
    val fromIndex = currentIndex - 1
    val toIndex   = currentIndex - 2
    if (toIndex < offset) {
      failed(StackUnderflow)
    } else {
      val tmp = underlying(fromIndex)
      underlying(fromIndex) = underlying(toIndex)
      underlying(toIndex) = tmp
      Right(())
    }
  }

  def remove(n: Int): ExeResult[Unit] = {
    if (n > size) {
      failed(StackUnderflow)
    } else if (n > 0) {
      currentIndex -= n
      Right(())
    } else {
      failed(NegativeArgumentInStack)
    }
  }

  def dupTop(): ExeResult[Unit] = {
    if (currentIndex >= 1) {
      push(underlying(currentIndex - 1))
    } else {
      failed(StackUnderflow)
    }
  }

  def remainingStack(): Stack[T] =
    new Stack[T](
      underlying,
      offset = currentIndex,
      currentIndex = currentIndex,
      maxIndex = maxIndex
    )

  // reserve n spots on top of the stack for method variables or contract fields
  def reserveForVars(n: Int): ExeResult[(VarVector[T], Stack[T])] = {
    val nextStackIndex = currentIndex + n
    if (nextStackIndex > maxIndex) {
      failed(StackOverflow)
    } else if (nextStackIndex >= currentIndex) {
      val varVector = VarVector.unsafe(underlying, currentIndex, n)
      val newStack =
        new Stack[T](
          underlying,
          offset = nextStackIndex,
          currentIndex = nextStackIndex,
          maxIndex = maxIndex
        )
      Right(varVector -> newStack)
    } else {
      failed(NegativeArgumentInStack)
    }
  }
}
