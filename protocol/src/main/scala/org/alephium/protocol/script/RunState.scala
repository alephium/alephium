package org.alephium.protocol.script

import scala.annotation.tailrec

import akka.util.ByteString

import org.alephium.crypto.ED25519Signature
import org.alephium.protocol.config.ScriptConfig
import org.alephium.util.AVector

object RunState {
  def empty(context: RunContext, signatures: AVector[ED25519Signature])(
      implicit config: ScriptConfig): RunState = {
    val signatureStack = Stack.popOnly(signatures)
    RunState(context, 0, Stack.empty, signatureStack)
  }
}

final case class RunState(context: RunContext,
                          var instructionCount: Int,
                          stack: Stack[ByteString],
                          signatures: Stack[ED25519Signature]) {
  def currentInstruction: Instruction = context.instructions(instructionCount)

  @tailrec
  def run(): RunResult[Unit] = {
    if (isTerminated) Right(())
    else {
      step() match {
        case Left(error) => Left(error)
        case Right(_)    => run()
      }
    }
  }

  def step(): RunResult[Unit] = {
    currentInstruction.runWith(this).map { _ =>
      instructionCount += 1
    }
  }

  def isTerminated: Boolean = instructionCount == context.instructions.length

  def isValidFinalState: Boolean = stack.isEmpty && signatures.isEmpty

  def reload(newContext: RunContext): RunState = {
    this.copy(context = newContext, instructionCount = 0)
  }
}
