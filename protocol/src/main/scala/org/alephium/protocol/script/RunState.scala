package org.alephium.protocol.script

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

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
                          var instructionIndex: Int,
                          stack: Stack[ByteString],
                          signatures: Stack[ED25519Signature]) {
  def currentInstruction: Instruction = context.instructions(instructionIndex)

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

  def runIf(condition: Boolean): RunResult[Unit] = {
    assume(currentInstruction == OP_IF)

    locateElseEndif(instructionIndex).flatMap {
      case (Some(elseIndex), endifIndex) =>
        val ifIndex = instructionIndex
        instructionIndex = endifIndex
        if (condition) run(context.instructions.slice(ifIndex + 1, elseIndex))
        else run(context.instructions.slice(elseIndex + 1, endifIndex))
      case (None, endifIndex) =>
        val ifIndex = instructionIndex
        instructionIndex = endifIndex
        if (condition) run(context.instructions.slice(ifIndex + 1, endifIndex))
        else Right(())
    }
  }

  //scalastyle:off return
  @SuppressWarnings(Array("org.wartremover.warts.While"))
  def locateElseEndif(currentIndex: Int): RunResult[(Option[Int], Int)] = {
    import RunStateUtils._

    val track = ArrayBuffer[(IfElseTrack, Int)](If -> currentIndex)
    var index = currentIndex + 1
    while (index < context.instructions.length) {
      context.instructions(index) match {
        case OP_IF =>
          track.append(If -> index)
        case OP_ELSE =>
          if (track(track.length - 1)._1 == Else) return Left(TooManyElses)
          track.append(Else -> index)
        case OP_ENDIF =>
          track(track.length - 1) match {
            case (If, _) =>
              if (track.length == 1) return Right(None -> index)
              else track.remove(track.length - 1)
            case (Else, elseIndex) =>
              if (track.length == 2) {
                assume(track(0)._1 == If)
                return Right(Some(elseIndex) -> index)
              } else {
                track.remove(track.length - 1)
                track.remove(track.length - 1)
              }
          }
        case _ => ()
      }
      index += 1
    }
    Left(IncompleteIfScript)
  }
  //scalastyle:on return

  def run(instructions: AVector[Instruction]): RunResult[Unit] = {
    val newState = reload(instructions)
    newState.run()
  }

  def step(): RunResult[Unit] = {
    currentInstruction.runWith(this).map { _ =>
      instructionIndex += 1
    }
  }

  def isTerminated: Boolean = instructionIndex == context.instructions.length

  def isValidFinalState: Boolean = stack.isEmpty && signatures.isEmpty

  def reload(instructions: AVector[Instruction]): RunState = {
    val newContext = context.copy(instructions = instructions)
    reload(newContext)
  }

  def reload(newContext: RunContext): RunState = {
    this.copy(context = newContext, instructionIndex = 0)
  }
}

case object RunStateUtils {
  sealed trait IfElseTrack
  case object If   extends IfElseTrack
  case object Else extends IfElseTrack
}
