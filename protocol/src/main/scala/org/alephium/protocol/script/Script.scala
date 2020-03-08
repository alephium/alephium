package org.alephium.protocol.script

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

import akka.util.ByteString

import org.alephium.crypto._
import org.alephium.util.AVector

object Script {
  type Stack = ArrayBuffer[ByteString]

  // It's mutable for efficiency
  private[script] class Context(val stack: ArrayBuffer[ByteString],
                                val signatures: ArrayBuffer[ByteString],
                                val data: ByteString) {
    def isEmpty: Boolean = stack.isEmpty && signatures.isEmpty
  }

  object Context {
    def apply(signatures: AVector[ByteString], data: ByteString): Context = {
      val sigBuffer = signatures.toArray.to[ArrayBuffer]
      new Context(ArrayBuffer.empty, sigBuffer, data)
    }
  }

  private type RunResult = Either[RunFailed, Unit]

  def run(data: ByteString, pubScript: PubScript, witness: Witness): RunResult = {
    val context = Context(witness.signatures, data)
    run(context, List(witness.privateScript, pubScript.instructions)).flatMap { _ =>
      if (context.isEmpty) Right(()) else Left(InvalidFinalState)
    }
  }

  @tailrec
  private[script] def run(context: Context,
                          instructionLists: List[AVector[Instruction]]): RunResult = {
    instructionLists match {
      case Nil => Right(())
      case instructions :: rest =>
        if (instructions.isEmpty) {
          run(context, rest)
        } else {
          val allRest = if (instructions.length == 1) rest else instructions.tail :: rest
          run(context, instructions.head) match {
            case Left(e)  => Left(e)
            case Right(_) => run(context, allRest)
          }
        }
    }
  }

  private[script] def run(context: Context, instruction: Instruction): RunResult = {
    import context._
    instruction match {
      case OP_PUSH(bytes) =>
        stack.append(bytes)
        Done
      case OP_EQUALVERIFY =>
        if (stack.size < 2) insufficientItems(instruction, stack)
        else {
          val item1 = pop(stack)
          val item2 = pop(stack)
          if (item1 == item2) Done else Left(VerificationFailed)
        }
      case OP_KECCAK256 =>
        if (stack.isEmpty) emptyStack(instruction)
        else {
          @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
          val hash = Keccak256.hash(stack.last)
          stack.append(hash.bytes)
          Done
        }
      case OP_CHECKSIG =>
        if (stack.isEmpty) emptyStack(instruction)
        if (signatures.isEmpty) error(instruction, "no signature available")
        else {
          val rawPublicKey = pop(stack)
          val rawSignature = pop(signatures)
          val okOpt = for {
            publicKey <- ED25519PublicKey.from(rawPublicKey)
            signature <- ED25519Signature.from(rawSignature)
          } yield ED25519.verify(data, signature, publicKey)
          okOpt match {
            case Some(true)  => Done
            case Some(false) => Left(VerificationFailed)
            case None        => error(instruction, s"invalid format for public key or signature")
          }
        }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def error(instruction: Instruction, message: String): RunResult = {
    Left(NonCategorized(s"${instruction.toString} failed, due to $message"))
  }

  def emptyStack(instruction: Instruction): RunResult = {
    error(instruction, "empty stack")
  }

  def insufficientItems(instruction: Instruction, stack: Stack): RunResult = {
    error(instruction, s"stack of only ${stack.size}")
  }

  // Unsafe! Make sure the stack is not empty
  private def pop(stack: Stack): ByteString = {
    assert(stack.nonEmpty)
    stack.remove(stack.size - 1)
  }

  private[script] val Done           = Right(())
  private[script] val OneOfTrueValue = ByteString(1)
  private[script] val False          = ByteString(0)
  private[script] def convert(ok: Boolean): ByteString = {
    if (ok) OneOfTrueValue else False
  }
}
