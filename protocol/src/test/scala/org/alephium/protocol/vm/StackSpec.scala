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

import org.scalatest.EitherValues._

import org.alephium.util.{AlephiumSpec, AVector}

class StackSpec extends AlephiumSpec {
  it should "initial stack properly" in {
    val stack0 = Stack.popOnly[Int](AVector(0, 1))
    stack0.underlying.size is 2
    stack0.offset is 0
    stack0.capacity is 2
    stack0.currentIndex is 2

    val stack1 = Stack.unsafe[Int](AVector(0, 1), 3)
    stack1.underlying.size is 3
    stack1.offset is 0
    stack1.capacity is 3
    stack1.currentIndex is 2
  }

  it should "push/pop/top" in {
    val stack = Stack.ofCapacity[Int](2)
    stack.isEmpty is true
    stack.pop().isLeft is true

    stack.push(1) isE ()
    stack.size is 1
    stack.top.get is 1
    stack.push(2) isE ()
    stack.top.get is 2
    stack.size is 2
    stack.push(3).leftValue isE StackOverflow
    stack.top.get is 2
    stack.size is 2

    val n1 = stack.pop().rightValue
    n1 is 2
    stack.size is 1
    val n2 = stack.pop().rightValue
    n2 is 1
    stack.top.isEmpty is true
    stack.size is 0
    stack.isEmpty
  }

  it should "push more elements than the initial capacity of the underlying array buffer" in {
    val n     = 6
    val stack = Stack.ofCapacity[Int](6)
    (0 until n).foreach(stack.push(_) isE ())
    stack.push(n).leftValue isE StackOverflow
  }

  it should "overflow when push too many elements" in {
    val stack = Stack.ofCapacity[Int](3)
    stack.push(AVector(1)) isE ()
    stack.size is 1
    stack.push(AVector(2, 3, 4)).leftValue isE StackOverflow
    stack.size is 1
    stack.push(AVector(2, 3)) isE ()
    stack.size is 3
  }

  it should "pop a number of elements" in {
    val stack = Stack.popOnly(AVector(1, 2, 3))
    stack.pop(4).leftValue isE StackUnderflow
    stack.size is 3
    stack.pop(2) isE AVector(2, 3)
    stack.pop(0) isE AVector.empty[Int]
    stack.pop(-1).leftValue isE NegativeArgumentInStack
  }

  it should "remove" in {
    val stack = Stack.ofCapacity[Int](3)

    stack.push(1) isE ()
    stack.push(2) isE ()
    stack.push(3) isE ()
    stack.remove(4).left.value isE StackUnderflow
    stack.remove(3).isRight is true
    stack.remove(-1).leftValue isE NegativeArgumentInStack
    stack.isEmpty is true
  }

  it should "dup" in {
    val stack = Stack.ofCapacity[Int](2)

    stack.dupTop().leftValue isE StackUnderflow

    stack.push(1)
    stack.size is 1

    stack.dupTop() isE ()
    stack.size is 2
    stack.underlying.toSeq.take(2) is Seq(1, 1)

    stack.dupTop().leftValue isE StackOverflow
  }

  it should "swap" in {
    val stack = Stack.ofCapacity[Int](2)

    stack.swapTopTwo().leftValue isE StackUnderflow

    stack.push(1)
    stack.size is 1
    stack.swapTopTwo().leftValue isE StackUnderflow

    stack.push(2)
    stack.size is 2
    stack.underlying.toSeq.take(2) is Seq(1, 2)
    stack.swapTopTwo() isE ()
    stack.underlying.toSeq.take(2) is Seq(2, 1)
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

  it should "create var vector" in {
    val stack             = Stack.ofCapacity[Int](3)
    val (vector0, stack0) = stack.reserveForVars(1).rightValue
    vector0.length is 1
    stack0.capacity is 2
    val (vector1, stack1) = stack.reserveForVars(3).rightValue
    vector1.length is 3
    stack1.capacity is 0
    stack.reserveForVars(4).leftValue isE StackOverflow
    stack.reserveForVars(-1).leftValue isE NegativeArgumentInStack

    stack.push(0)
    val (vector2, stack2) = stack.reserveForVars(1).rightValue
    vector2.length is 1
    stack2.capacity is 1
    val (vector3, stack3) = stack.reserveForVars(2).rightValue
    vector3.length is 2
    stack3.capacity is 0
    stack.reserveForVars(3).leftValue isE StackOverflow
    stack.reserveForVars(-1).leftValue isE NegativeArgumentInStack
  }
}
