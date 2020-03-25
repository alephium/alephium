package org.alephium.protocol.script

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.protocol.config.ScriptConfig
import org.alephium.util.{AlephiumSpec, AVector}

class StackSpec extends AlephiumSpec {
  it should "push/pop/peek" in {
    implicit val config: ScriptConfig = new ScriptConfig { override def maxStackSize: Int = 2 }

    val stack = Stack.empty[Int]
    stack.isEmpty is true
    stack.pop().isLeft is true

    stack.push(1).isRight is true
    stack.size is 1
    stack.peek(1).right.value is 1
    stack.peek(0).left.value is StackOverflow
    stack.peek(2).left.value is StackUnderflow
    stack.push(2).isRight is true
    stack.peek(1).right.value is 2
    stack.peek(2).right.value is 1
    stack.peek(3).left.value is StackUnderflow
    stack.size is 2
    stack.push(3).isLeft is true
    stack.peek(1).right.value is 2
    stack.peek(2).right.value is 1
    stack.size is 2

    val n1 = stack.pop().right.value
    n1 is 2
    stack.peek(1).right.value is 1
    stack.size is 1
    val n2 = stack.pop().right.value
    n2 is 1
    stack.peek(1).isLeft is true
    stack.size is 0
    stack.isEmpty
  }

  it should "pop a number of elements" in {
    val stack = Stack.unsafe(AVector(1, 2, 3), 3)
    stack.pop(4).left.value is StackUnderflow
    stack.size is 3
    stack.pop(2).right.value is AVector(2, 3)
  }

  it should "swap/remove" in {
    implicit val config: ScriptConfig = new ScriptConfig { override def maxStackSize: Int = 3 }

    val stack = Stack.empty[Int]
    def check(stack: Stack[Int], i1: Int, i2: Int, i3: Int): Assertion = {
      stack.peek(1).right.value is i1
      stack.peek(2).right.value is i2
      stack.peek(3).right.value is i3
    }

    stack.push(1)
    stack.push(2)
    stack.push(3)
    check(stack, 3, 2, 1)
    stack.swap(4).left.value is StackUnderflow
    stack.swap(3).isRight is true
    check(stack, 1, 2, 3)
    stack.swap(2).isRight is true
    check(stack, 2, 1, 3)
    stack.swap(1).isLeft is true
    stack.remove(4).left.value is StackUnderflow
    stack.remove(3).isRight is true
    stack.isEmpty is true
  }
}
