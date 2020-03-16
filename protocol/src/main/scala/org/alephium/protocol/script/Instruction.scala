package org.alephium.protocol.script

import akka.util.ByteString

import org.alephium.crypto.{ED25519, ED25519PublicKey, ED25519Signature, Keccak256}
import org.alephium.serde._
import org.alephium.util.AVector

sealed trait Instruction {
  def runWith(state: RunState): RunResult[RunState]
}

object Instruction {
  //scalastyle:off magic.number
  implicit val serde: Serde[Instruction] = new Serde[Instruction] {
    override def serialize(input: Instruction): ByteString = input match {
      case x: OP_PUSH     => ByteString.apply(0x01) ++ bytestringSerde.serialize(x.bytes)
      case x: OP_DUP      => ByteString.apply(0x02) ++ intSerde.serialize(x.index)
      case OP_POP         => ByteString.apply(0x03)
      case x: OP_SWAP     => ByteString.apply(0x04) ++ intSerde.serialize(x.index)
      case OP_EQUALVERIFY => ByteString.apply(0x05)
      case OP_KECCAK256   => ByteString.apply(0x06)
      case OP_CHECKSIG    => ByteString.apply(0x07)
    }

    override def _deserialize(input: ByteString): SerdeResult[(Instruction, ByteString)] = {
      byteSerde._deserialize(input).flatMap {
        case (opCode, rest) =>
          opCode match {
            case 0x01 =>
              bytestringSerde._deserialize(rest).map {
                case (bytes, rest1) => (OP_PUSH(bytes), rest1)
              }
            case 0x02 =>
              intSerde._deserialize(rest).flatMap {
                case (index, rest1) =>
                  OP_DUP.from(index) match {
                    case Some(dup) => Right((dup, rest1))
                    case None      => Left(SerdeError.validation(s"Invalid DUP index: $index"))
                  }
              }
            case 0x03 => Right((OP_POP, rest))
            case 0x04 =>
              intSerde._deserialize(rest).flatMap {
                case (index, rest1) =>
                  OP_SWAP.from(index) match {
                    case Some(swap) => Right((swap, rest1))
                    case None       => Left(SerdeError.validation(s"Invalid SWAP index: $index"))
                  }
              }
            case 0x05 => Right((OP_EQUALVERIFY, rest))
            case 0x06 => Right((OP_KECCAK256, rest))
            case 0x07 => Right((OP_CHECKSIG, rest))
          }
      }
    }
  }
  //scalastyle:on magic.number

  def pop(signatures: AVector[ED25519Signature])
    : RunResult[(ED25519Signature, AVector[ED25519Signature])] = {
    if (signatures.isEmpty) Left(InsufficientSignatures)
    else Right((signatures.last, signatures.init))
  }

  def decodePublicKey(bytes: ByteString): RunResult[ED25519PublicKey] = {
    ED25519PublicKey.from(bytes) match {
      case Some(key) => Right(key)
      case None      => Left(InvalidPublicKey)
    }
  }

  def verify(rawData: ByteString,
             signature: ED25519Signature,
             publicKey: ED25519PublicKey): RunResult[Unit] = {
    if (ED25519.verify(rawData, signature, publicKey)) Right(()) else Left(VerificationFailed)
  }
}

// Stack Instructions
final case class OP_PUSH(bytes: ByteString) extends Instruction {
  override def runWith(state: RunState): RunResult[RunState] = {
    val stack = state.stack
    for {
      newStack <- stack.push(bytes)
    } yield state.update(newStack)
  }
}

sealed abstract case class OP_DUP(index: Int) extends Instruction {
  override def runWith(state: RunState): RunResult[RunState] = {
    val stack = state.stack
    for {
      topElement <- stack.peek(index)
      newStack   <- stack.push(topElement)
    } yield state.update(newStack)
  }
}
object OP_DUP {
  @inline private def validate(index: Int): Boolean = index > 0

  def from(index: Int): Option[OP_DUP] = {
    if (validate(index)) Some(new OP_DUP(index) {}) else None
  }

  def unsafe(index: Int): OP_DUP = {
    assume(validate(index))
    new OP_DUP(index) {}
  }
}

case object OP_POP extends Instruction {
  override def runWith(state: RunState): RunResult[RunState] = {
    val stack = state.stack
    for {
      bytes_newStack <- stack.pop()
    } yield state.update(bytes_newStack._2)
  }
}

sealed abstract case class OP_SWAP(index: Int) extends Instruction {
  override def runWith(state: RunState): RunResult[RunState] = {
    val stack = state.stack
    for {
      newStack <- stack.swap(index)
    } yield state.update(newStack)
  }
}
object OP_SWAP {
  def validate(index: Int): Boolean = index > 1

  def from(index: Int): Option[OP_SWAP] = {
    if (validate(index)) Some(new OP_SWAP(index) {}) else None
  }

  def unsafe(index: Int): OP_SWAP = {
    assume(validate(index))
    new OP_SWAP(index) {}
  }
}

// BitwiseInstructions
case object OP_EQUALVERIFY extends Instruction {
  def checkPoped(item0: ByteString, item1: ByteString): RunResult[Unit] = {
    if (item0 == item1) Right(()) else Left(VerificationFailed)
  }

  override def runWith(state: RunState): RunResult[RunState] = {
    val stack = state.stack
    for {
      item0_stack0 <- stack.pop()
      item1_stack1 <- item0_stack0._2.pop()
      _            <- checkPoped(item0_stack0._1, item1_stack1._1)
    } yield state.update(item1_stack1._2)
  }
}

// Crypto Instructions
case object OP_KECCAK256 extends Instruction {
  override def runWith(state: RunState): RunResult[RunState] = {
    val stack = state.stack
    for {
      bytes_stack0 <- stack.pop()
      stack1       <- bytes_stack0._2.push(Keccak256.hash(bytes_stack0._1).bytes)
    } yield state.update(stack1)
  }
}
case object OP_CHECKSIG extends Instruction {
  override def runWith(state: RunState): RunResult[RunState] = {
    val rawData    = state.context.rawData
    val stack      = state.stack
    val signatures = state.signatures
    for {
      rawPublicKey_stack0   <- stack.pop()
      signature_signatures0 <- Instruction.pop(signatures)
      publicKey             <- Instruction.decodePublicKey(rawPublicKey_stack0._1)
      _                     <- Instruction.verify(rawData, signature_signatures0._1, publicKey)
    } yield state.update(rawPublicKey_stack0._2, signature_signatures0._2)
  }
}
