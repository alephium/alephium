package org.alephium.protocol.script

import scala.reflect.ClassTag

import org.alephium.protocol.config.ScriptConfig
import org.alephium.util.AVector

object Stack {
  def empty[T: ClassTag](implicit config: ScriptConfig): Stack[T] = {
    val underlying = Array.ofDim[T](config.maxStackSize)
    new Stack(underlying, 0)
  }

  def unsafe[T: ClassTag](elems: AVector[T])(implicit config: ScriptConfig): Stack[T] = {
    assume(elems.length <= config.maxStackSize)
    val underlying = Array.ofDim[T](config.maxStackSize)
    elems.foreachWithIndex((elem, index) => underlying(index) = elem)
    new Stack(underlying, elems.length)
  }
}

// Note: current place at underlying is empty
class Stack[T] private (underlying: Array[T], currentIndex: Int) {
  def isEmpty: Boolean = currentIndex == 0

  def size: Int = currentIndex

  def push(elem: T): RunResult[Stack[T]] = {
    if (currentIndex < underlying.length) {
      underlying(currentIndex) = elem
      Right(new Stack(underlying, currentIndex + 1))
    } else {
      Left(StackOverflow)
    }
  }

  def pop(): RunResult[(T, Stack[T])] = {
    if (currentIndex > 0) {
      val elem     = underlying(currentIndex - 1)
      val newStack = new Stack(underlying, currentIndex - 1)
      Right((elem, newStack))
    } else {
      Left(StackUnderflow)
    }
  }

  def peek(): RunResult[T] = {
    if (currentIndex > 0) {
      Right(underlying(currentIndex - 1))
    } else {
      Left(StackUnderflow)
    }
  }
}
