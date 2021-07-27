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

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.util.{AlephiumSpec, AVector}

class StackSpec extends AlephiumSpec {
  it should "push/pop/peek" in {
    val stack = Stack.ofCapacity[Int](2)
    stack.isEmpty is true
    stack.pop().isLeft is true

    stack.push(1).isRight is true
    stack.size is 1
    stack.peek(1) isE 1
    stack.peek(0).left.value isE StackOverflow
    stack.peek(2).left.value isE StackUnderflow
    stack.push(2).isRight is true
    stack.peek(1) isE 2
    stack.peek(2) isE 1
    stack.peek(3).left.value isE StackUnderflow
    stack.size is 2
    stack.push(3).isLeft is true
    stack.peek(1) isE 2
    stack.peek(2) isE 1
    stack.size is 2

    val n1 = stack.pop().toOption.get
    n1 is 2
    stack.peek(1) isE 1
    stack.size is 1
    val n2 = stack.pop().toOption.get
    n2 is 1
    stack.peek(1).isLeft is true
    stack.size is 0
    stack.isEmpty
  }

  it should "push many elements than initial capacity of underlying array buffer" in {
    val n     = 6
    val stack = Stack.ofCapacity[Int](6)
    (0 until n).foreach(stack.push(_) isE ())
    stack.push(n).isLeft is true
  }

  it should "pop a number of elements" in {
    val stack = Stack.unsafe(AVector(1, 2, 3), 3)
    stack.pop(4).left.value isE StackUnderflow
    stack.size is 3
    stack.pop(2) isE AVector(2, 3)
  }

  it should "swap/remove" in {
    val stack = Stack.ofCapacity[Int](3)
    def check(stack: Stack[Int], i1: Int, i2: Int, i3: Int): Assertion = {
      stack.peek(1) isE i1
      stack.peek(2) isE i2
      stack.peek(3) isE i3
    }

    stack.push(1)
    stack.push(2)
    stack.push(3)
    check(stack, 3, 2, 1)
    stack.swap(4).left.value isE StackUnderflow
    stack.swap(3).isRight is true
    check(stack, 1, 2, 3)
    stack.swap(2).isRight is true
    check(stack, 2, 1, 3)
    stack.swap(1).isLeft is true
    stack.remove(4).left.value isE StackUnderflow
    stack.remove(3).isRight is true
    stack.isEmpty is true
  }

  it should "create sub stack" in {
    val n     = 6
    val stack = Stack.ofCapacity[Int](n)

    (0 until n) foreach { k =>
      stack.push(k) isE ()
      val remainingStack = stack.remainingStack()
      remainingStack.capacity is (n - k - 1)
    }
    stack.size is n
    stack.remove(n) isE ()
    stack.size is 0

    (0 until n).foldLeft(stack.remainingStack()) { case (remainingStack, k) =>
      remainingStack.currentIndex is k
      remainingStack.push(k) isE ()
      remainingStack.size is 1
      remainingStack.remainingStack()
    }
    stack.size is 0
  }
}
