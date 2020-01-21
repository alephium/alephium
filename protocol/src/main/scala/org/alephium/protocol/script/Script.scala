package org.alephium.protocol.script

import scala.collection.mutable.ArrayBuffer

import akka.util.ByteString

import org.alephium.crypto.{ED25519, ED25519PublicKey, ED25519Signature, Keccak256}
import org.alephium.util.AVector

object Script {
  type Stack     = ArrayBuffer[ByteString]
  type PubScript = AVector[Instruction]
  type Witness   = AVector[Instruction]
  type Context   = ByteString

  private type ExeResult0 = Either[ExeFailed, Stack]

  def run(context: Context, pubScript: PubScript, witness: Witness): ExeResult = {
    run(context, ArrayBuffer.empty, pubScript, witness) match {
      case Left(failure) => failure
      case Right(stack) =>
        if (stack.isEmpty) ExeSuccessful else InvalidFinalStack
    }
  }

  private[script] def run(context: Context,
                          stack: Stack,
                          pubScript: PubScript,
                          witness: Witness): ExeResult0 = {
    run(context, stack, List(witness, pubScript))
  }

  private[script] def run(context: Context,
                          stack: Stack,
                          instructionLists: List[AVector[Instruction]]): ExeResult0 = {
    instructionLists match {
      case Nil => Right(stack)
      case instructions :: rest =>
        val allRest = if (instructions.length == 1) rest else instructions.tail :: rest
        run(context, stack, instructions.head).flatMap(run(context, _, allRest))
    }
  }

  private[script] def run(context: Context, stack: Stack, instruction: Instruction): ExeResult0 =
    instruction match {
      case OP_PUSH(bytes) =>
        stack.append(bytes)
        Right(stack)
      case OP_EQUALVERIFY =>
        if (stack.size < 2) insufficientItems(instruction, stack)
        else {
          val item1 = pop(stack)
          val item2 = pop(stack)
          if (item1 == item2) Right(stack) else Left(VerificationFailed)
        }
      case OP_KECCAK256 =>
        if (stack.isEmpty) emptyStack(instruction)
        else {
          val hash = Keccak256.hash(stack.last)
          stack.append(hash.bytes)
          Right(stack)
        }
      case OP_CHECKSIG =>
        if (stack.size < 2) insufficientItems(instruction, stack)
        else {
          val rawPublicKey = pop(stack)
          val rawSignature = pop(stack)
          if (rawPublicKey.length != ED25519PublicKey.length) {
            error(instruction, s"public key of size ${rawPublicKey.length}")
          } else if (rawSignature.length != ED25519Signature.length) {
            error(instruction, s"signature of size ${rawSignature.length}")
          } else {
            val publicKey = ED25519PublicKey.unsafeFrom(rawPublicKey)
            val signature = ED25519Signature.unsafeFrom(rawSignature)
            val ok        = ED25519.verify(context, signature, publicKey)
            if (ok) Right(stack) else Left(VerificationFailed)
          }
        }
    }

  def error(instruction: Instruction, message: String): ExeResult0 = {
    Left(NonCategorized(s"${instruction.toString} failed, due to $message"))
  }

  def emptyStack(instruction: Instruction): ExeResult0 = {
    error(instruction, "empty stack")
  }

  def insufficientItems(instruction: Instruction, stack: Stack): ExeResult0 = {
    error(instruction, s"stack of only ${stack.size}")
  }

  // Unsafe! Make sure the stack is not empty
  private def pop(stack: Stack): ByteString = {
    assert(stack.nonEmpty)
    stack.remove(stack.size - 1)
  }

  private[script] val OneOfTrueValue = ByteString(1)
  private[script] val False          = ByteString(0)
  private[script] def convert(ok: Boolean): ByteString = {
    if (ok) OneOfTrueValue else False
  }
}
