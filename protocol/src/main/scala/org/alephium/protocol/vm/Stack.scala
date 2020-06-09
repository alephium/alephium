package org.alephium.protocol.vm

import scala.{specialized => sp}
import scala.reflect.ClassTag

import org.alephium.protocol.config.ScriptConfig
import org.alephium.util.AVector

object Stack {
  def empty[T: ClassTag](implicit config: ScriptConfig): Stack[T] = {
    val underlying = Array.ofDim[T](config.maxStackSize)
    new Stack(underlying, 0)
  }

  def ofCapacity[T: ClassTag](capacity: Int): Stack[T] = {
    val underlying = Array.ofDim[T](capacity)
    new Stack(underlying, 0)
  }

  def popOnly[T: ClassTag](elems: AVector[T]): Stack[T] = {
    unsafe(elems, elems.length)
  }

  def unsafe[T: ClassTag](elems: AVector[T], maxSize: Int): Stack[T] = {
    assume(elems.length <= maxSize)
    val underlying = Array.ofDim[T](maxSize)
    elems.foreachWithIndex((elem, index) => underlying(index) = elem)
    new Stack(underlying, elems.length)
  }
}

// Note: current place at underlying is empty
class Stack[@sp T: ClassTag] private (val
                                      underlying: Array[T],
                                      var currentIndex: Int) {
  def isEmpty: Boolean = currentIndex == 0

  def size: Int = currentIndex

  def topUnsafe: T = {
    assume(currentIndex >= 1)
    underlying(currentIndex - 1)
  }

  def push(elem: T): ExeResult[Unit] = {
    if (currentIndex < underlying.length) {
      underlying(currentIndex) = elem
      currentIndex += 1
      Right(())
    } else {
      Left(StackOverflow)
    }
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
    assume(n > 0)
    if (n <= size) {
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
