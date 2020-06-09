package org.alephium.protocol.vm

import scala.annotation.tailrec

import org.alephium.util.AVector

case class Runtime[Ctx <: Context](stack: Stack[Frame[Ctx]], var returnTo: Option[Val] = None)

trait VM[Ctx <: Context] {
  def execute(script: Script[Ctx], fields: AVector[Val], args: AVector[Val]): ExeResult[Val] = {
    val stack = Stack.ofCapacity[Frame[Ctx]](stackMaxSize)
    val rt    = Runtime[Ctx](stack)

    stack.push(script.startFrame(fields, args, value => rt.returnTo = Some(value)))
    execute(stack).flatMap(_ => rt.returnTo.toRight(NoReturnVal))
  }

  @tailrec
  private def execute(stack: Stack[Frame[Ctx]]): ExeResult[Unit] = {
    if (!stack.isEmpty) {
      val currentFrame = stack.topUnsafe
      if (currentFrame.isComplete) {
        stack.pop()
        execute(stack)
      } else {
        currentFrame.execute()
      }
    } else {
      Right(())
    }
  }
}

object StatelessVM extends VM[StatelessContext]

object StatefulVM extends VM[StatefulContext]
