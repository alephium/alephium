package org.alephium.protocol.vm

import scala.{specialized => sp}
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

import org.alephium.util.AVector

object Stack {
  @inline private def newBuffer[T: ClassTag] = new ArrayBuffer[T](4)

  def ofCapacity[T: ClassTag](capacity: Int): Stack[T] = {
    val underlying = newBuffer[T]
    new Stack(underlying, capacity, 0)
  }

  def popOnly[T: ClassTag](elems: AVector[T]): Stack[T] = {
    unsafe(elems, elems.length)
  }

  def unsafe[T: ClassTag](elems: AVector[T], maxSize: Int): Stack[T] = {
    assume(elems.length <= maxSize)
    val underlying = ArrayBuffer.from(elems.toIterable)
    new Stack(underlying, maxSize, elems.length)
  }

  def unsafe[T: ClassTag](elems: ArrayBuffer[T], maxSize: Int): Stack[T] = {
    assume(elems.length <= maxSize)
    new Stack(elems, maxSize, elems.length)
  }
}

// Note: current place at underlying is empty
class Stack[@sp T: ClassTag] private (val underlying: ArrayBuffer[T],
                                      val capacity: Int,
                                      var currentIndex: Int) {
  def isEmpty: Boolean = currentIndex == 0

  def size: Int = currentIndex

  def topUnsafe: T = {
    underlying(currentIndex - 1)
  }

  @inline private def push(elem: T, index: Int): Unit = {
    if (index < underlying.length) underlying(index) = elem
    else underlying.append(elem)
  }

  def push(elem: T): ExeResult[Unit] = {
    if (currentIndex < capacity) {
      push(elem, currentIndex)
      currentIndex += 1
      Right(())
    } else {
      Left(StackOverflow)
    }
  }

  def push(elems: AVector[T]): ExeResult[Unit] = {
    if (size + elems.length <= capacity) {
      elems.foreachWithIndex((elem, index) => push(elem, currentIndex + index))
      currentIndex += elems.length
      Right(())
    } else Left(StackOverflow)
  }

  def pop(): ExeResult[T] = {
    val elemIndex = currentIndex - 1
    if (elemIndex >= 0) {
      val elem = underlying(elemIndex)
      currentIndex = elemIndex
      Right(elem)
    } else {
      Left(StackUnderflow)
    }
  }

  def pop(n: Int): ExeResult[AVector[T]] = {
    if (n == 0) {
      Right(AVector.ofSize(0))
    } else if (n <= size) {
      val start = currentIndex - n
      val elems = AVector.tabulate(n) { k =>
        underlying(start + k)
      }
      currentIndex = start
      Right(elems)
    } else Left(StackUnderflow)
  }

  def remove(total: Int): ExeResult[Unit] = {
    if (size < total) Left(StackUnderflow)
    else {
      currentIndex -= total
      Right(())
    }
  }

  // Note: index starts from 1
  def peek(index: Int): ExeResult[T] = {
    val elemIndex = currentIndex - index
    if (index < 1) {
      Left(StackOverflow)
    } else if (elemIndex < 0) {
      Left(StackUnderflow)
    } else {
      Right(underlying(elemIndex))
    }
  }

  // Note: index starts from 1
  def dup(index: Int): ExeResult[Unit] = {
    peek(index).flatMap(push)
  }

  // Note: index starts from 2
  def swap(index: Int): ExeResult[Unit] = {
    val fromIndex = currentIndex - 1
    val toIndex   = currentIndex - index
    if (index <= 1) {
      Left(StackOverflow)
    } else if (toIndex < 0) {
      Left(StackUnderflow)
    } else {
      val tmp = underlying(fromIndex)
      underlying(fromIndex) = underlying(toIndex)
      underlying(toIndex)   = tmp
      Right(())
    }
  }
}
