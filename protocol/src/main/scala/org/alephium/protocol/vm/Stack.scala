package org.alephium.protocol.vm

import scala.{specialized => sp}
import scala.collection.mutable
import scala.reflect.ClassTag

import org.alephium.util.AVector

object Stack {
  def ofCapacity[T: ClassTag](capacity: Int): Stack[T] = {
    val underlying = mutable.ArraySeq.make(new Array[T](capacity))
    new Stack(underlying, 0, capacity, 0)
  }

  def unsafe[T: ClassTag](elems: AVector[T], maxSize: Int): Stack[T] = {
    assume(elems.length <= maxSize)
    val underlying = mutable.ArraySeq.make(elems.toArray)
    unsafe(underlying, maxSize)
  }

  def unsafe[T: ClassTag](elems: mutable.ArraySeq[T], maxSize: Int): Stack[T] = {
    assume(elems.length <= maxSize)
    new Stack(elems, 0, maxSize, elems.length)
  }
}

// Note: current place at underlying is empty
class Stack[@sp T: ClassTag](val underlying: mutable.ArraySeq[T],
                             val offset: Int,
                             val capacity: Int,
                             var currentIndex: Int) {
  val maxIndex: Int = offset + capacity

  def isEmpty: Boolean = currentIndex == offset

  def nonEmpty: Boolean = currentIndex != offset

  def size: Int = currentIndex - offset

  def topUnsafe: T = {
    underlying(currentIndex - 1)
  }

  def push(elem: T): ExeResult[Unit] = {
    if (currentIndex < maxIndex) {
      underlying(currentIndex) = elem
      currentIndex += 1
      Right(())
    } else {
      Left(StackOverflow)
    }
  }

  def push(elems: AVector[T]): ExeResult[Unit] = {
    if (currentIndex + elems.length <= maxIndex) {
      elems.foreachWithIndex((elem, index) => underlying(currentIndex + index) = elem)
      currentIndex += elems.length
      Right(())
    } else Left(StackOverflow)
  }

  def pop(): ExeResult[T] = {
    val elemIndex = currentIndex - 1
    if (elemIndex >= offset) {
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
      val start = currentIndex - n // always >= offset
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
    } else if (elemIndex < offset) {
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
    } else if (toIndex < offset) {
      Left(StackUnderflow)
    } else {
      val tmp = underlying(fromIndex)
      underlying(fromIndex) = underlying(toIndex)
      underlying(toIndex)   = tmp
      Right(())
    }
  }

  def subStack(): Stack[T] =
    new Stack[T](underlying, currentIndex, maxIndex - currentIndex, currentIndex)
}
