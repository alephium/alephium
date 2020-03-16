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

    stack.push(1).isRight is true
    stack.size is 1
    stack.push(2).isRight is true
    stack.size is 2
    stack.push(3).isLeft is true
    stack.size is 2

    val n1 = stack.pop().right.value
    n1 is 2
    stack.size is 1
    val n2 = stack.pop().right.value
    n2 is 1
    stack.size is 0
    stack.isEmpty
  }
}
