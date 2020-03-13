package org.alephium.protocol.script

import org.scalatest.EitherValues._

import org.alephium.protocol.config.ScriptConfig
import org.alephium.util.AlephiumSpec

class StackSpec extends AlephiumSpec {
  implicit val config: ScriptConfig = new ScriptConfig { override def maxStackSize: Int = 2 }

  it should "work like a stack" in {
    val stack = Stack.empty[Int]
    stack.isEmpty is true
    stack.pop().isLeft is true

    val stack1 = stack.push(1).right.value
    stack1.size is 1
    val stack2 = stack1.push(2).right.value
    stack1.size is 1
    stack2.size is 2
    stack2.push(3).isLeft is true
    stack2.size is 2

    val (n3, stack3) = stack2.pop().right.value
    n3 is 2
    stack2.size is 2
    stack3.size is 1
    val (n4, stack4) = stack3.pop().right.value
    n4 is 1
    stack3.size is 1
    stack4.size is 0
    stack4.isEmpty
  }
}
