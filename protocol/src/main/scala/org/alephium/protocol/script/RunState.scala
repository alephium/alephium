package org.alephium.protocol.script

import scala.annotation.tailrec

import akka.util.ByteString

import org.alephium.crypto.ED25519Signature
import org.alephium.protocol.config.ScriptConfig
import org.alephium.util.AVector

object RunState {
  def empty(context: RunContext, signatures: AVector[ED25519Signature])(
      implicit config: ScriptConfig): RunState =
    RunState(context, 0, Stack.empty, signatures)

  @tailrec
  def run(state: RunState): RunResult[RunState] = {
    if (state.isTerminated) Right(state)
    else {
      state.currentInstruction.runWith(state) match {
        case Left(error)     => Left(error)
        case Right(newState) => run(newState)
      }
    }
  }
}

final case class RunState(context: RunContext,
                          instructionCount: Int,
                          stack: Stack[ByteString],
                          signatures: AVector[ED25519Signature]) {
  def currentInstruction: Instruction = context.instructions(instructionCount)

  def run(): RunResult[RunState] = RunState.run(this)

  def isTerminated: Boolean = instructionCount == context.instructions.length

  def isValidFinalState: Boolean = stack.isEmpty && signatures.isEmpty

  def load(newContext: RunContext): RunState =
    this.copy(context = newContext, instructionCount = 0)

  def update(newStack: Stack[ByteString]): RunState =
    this.copy(stack = newStack, instructionCount = instructionCount + 1)

  def update(newState: Stack[ByteString], restSignatures: AVector[ED25519Signature]): RunState =
    this.copy(stack            = newState,
              instructionCount = instructionCount + 1,
              signatures       = restSignatures)
}
