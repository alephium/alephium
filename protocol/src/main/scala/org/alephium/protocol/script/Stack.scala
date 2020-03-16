package org.alephium.protocol.script

import scala.reflect.ClassTag

import org.alephium.protocol.config.ScriptConfig
import org.alephium.util.AVector

object Stack {
  def empty[T: ClassTag](implicit config: ScriptConfig): Stack[T] = {
    val underlying = Array.ofDim[T](config.maxStackSize)
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
class Stack[T] private (underlying: Array[T], var currentIndex: Int) {
  def isEmpty: Boolean = currentIndex == 0

  def size: Int = currentIndex

  def push(elem: T): RunResult[Unit] = {
    if (currentIndex < underlying.length) {
      underlying(currentIndex) = elem
      currentIndex += 1
      Right(())
    } else {
      Left(StackOverflow)
    }
  }

  def pop(): RunResult[T] = {
    val elemIndex = currentIndex - 1
    if (elemIndex >= 0) {
      val elem = underlying(elemIndex)
      currentIndex = elemIndex
      Right(elem)
    } else {
      Left(StackUnderflow)
    }
  }

  // Note: index starts from 1
  def peek(index: Int): RunResult[T] = {
    val elemIndex = currentIndex - index
    if (index < 1) {
      Left(IndexUnderflow)
    } else if (elemIndex < 0) {
      Left(IndexOverflow)
    } else {
      Right(underlying(elemIndex))
    }
  }

  // Note: index starts from 2
  def swap(index: Int): RunResult[Unit] = {
    val fromIndex = currentIndex - 1
    val toIndex   = currentIndex - index
    if (index <= 1) {
      Left(IndexUnderflow)
    } else if (toIndex < 0) {
      Left(IndexOverflow)
    } else {
      val tmp = underlying(fromIndex)
      underlying(fromIndex) = underlying(toIndex)
      underlying(toIndex)   = tmp
      Right(())
    }
  }
}
