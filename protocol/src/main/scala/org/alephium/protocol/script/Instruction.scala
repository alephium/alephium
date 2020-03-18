package org.alephium.protocol.script

import akka.util.ByteString

import org.alephium.crypto.{ED25519, ED25519PublicKey, ED25519Signature, Keccak256}
import org.alephium.macros.EnumerationMacros
import org.alephium.serde._
import org.alephium.util.Bits

//scalastyle:off magic.number

sealed trait Instruction {
  def runWith(state: RunState): RunResult[Unit]

  def serialize(): ByteString
}

object Instruction {
  private val codeRegistry: Array[Option[InstructionCompanion]] = Array.fill(0x100)(None)
  implicit val ordering: Ordering[Registrable]                  = Ordering.by(_.hashCode())
  EnumerationMacros.sealedInstancesOf[Registrable].foreach(_.register())

  def register(from: Int, to: Int, obj: InstructionCompanion): Unit = {
    if (from >= 0 && from <= to && to <= 0xFF) {
      (from to to).foreach { index =>
        codeRegistry(index) match {
          case Some(_) => throw new RuntimeException(s"Instruction - @$index is registered")
          case None    => codeRegistry(index) = Some(obj)
        }
      }
    } else throw new RuntimeException("Instruction registery range is invalid")
  }

  def register(index: Int, obj: InstructionCompanion): Unit =
    register(index, index, obj)

  def register(index: Byte, obj: InstructionCompanion): Unit =
    register(Bits.toPosInt(index), obj)

  def getRegistered(byte: Byte): Option[InstructionCompanion] = {
    val index = Bits.toPosInt(byte)
    codeRegistry(index)
  }

  implicit val serde: Serde[Instruction] = new Serde[Instruction] {
    override def serialize(input: Instruction): ByteString = input.serialize()

    override def _deserialize(input: ByteString): SerdeResult[(Instruction, ByteString)] = {
      input.headOption match {
        case Some(code) =>
          getRegistered(code) match {
            case Some(obj) => obj.deserialize(input)
            case None      => Left(SerdeError.validation(s"Instruction - invalid code $code"))
          }
        case None => Left(SerdeError.notEnoughBytes(1, 0))
      }
    }
  }

  private[script] def decodePublicKey(bytes: ByteString): RunResult[ED25519PublicKey] = {
    ED25519PublicKey.from(bytes) match {
      case Some(key) => Right(key)
      case None      => Left(InvalidPublicKey)
    }
  }

  private[script] def verify(rawData: ByteString,
                             signature: ED25519Signature,
                             publicKey: ED25519PublicKey): RunResult[Unit] = {
    if (ED25519.verify(rawData, signature, publicKey)) Right(()) else Left(VerificationFailed)
  }

  private[script] def safeHead(bytes: ByteString): SerdeResult[(Byte, ByteString)] = {
    bytes.headOption match {
      case Some(head) => Right((head, bytes.drop(1)))
      case None       => Left(SerdeError.notEnoughBytes(1, bytes.length))
    }
  }

  private[script] def safeTake(bytes: ByteString,
                               length: Int): SerdeResult[(ByteString, ByteString)] = {
    if (bytes.length >= length) Right(bytes.splitAt(length))
    else Left(SerdeError.notEnoughBytes(length, bytes.length))
  }
}

sealed trait InstructionCompanion {
  def deserialize(input: ByteString): SerdeResult[(Instruction, ByteString)]
}

sealed trait Registrable {
  def register(): Unit
}

sealed trait SimpleInstruction extends Instruction with InstructionCompanion {
  def code: Byte

  override def serialize(): ByteString = ByteString(code)

  override def deserialize(input: ByteString): SerdeResult[(Instruction, ByteString)] = {
    Instruction.safeHead(input).flatMap {
      case (codeRaw, rest) =>
        if (codeRaw == code) Right((this, rest))
        else Left(SerdeError.validation(s"OP_POP - invalid code"))
    }
  }
}

// Stack Instructions
sealed abstract case class OP_PUSH(bytes: ByteString) extends Instruction {
  override def runWith(state: RunState): RunResult[Unit] = {
    state.stack.push(bytes)
  }

  override def serialize(): ByteString = {
    bytes.length match {
      case 1                       => ByteString(0x00) ++ bytes
      case 2                       => ByteString(0x01) ++ bytes
      case 4                       => ByteString(0x02) ++ bytes
      case 8                       => ByteString(0x03) ++ bytes
      case 16                      => ByteString(0x04) ++ bytes
      case 32                      => ByteString(0x05) ++ bytes
      case 64                      => ByteString(0x06) ++ bytes
      case n if n > 0 && n <= 0xFF => ByteString(0x07, bytes.length) ++ bytes
      case n                       => throw new RuntimeException(s"OP_PUSH - Invalid bytes length $n")
    }
  }
}

object OP_PUSH extends InstructionCompanion with Registrable {
  override def register(): Unit =
    Instruction.register(0x00, 0x07, this)

  @inline private def validate(bytes: ByteString): Boolean =
    bytes.nonEmpty && bytes.length <= 0xFF

  def from(bytes: ByteString): Either[String, OP_PUSH] = {
    if (validate(bytes)) Right(new OP_PUSH(bytes) {})
    else Left(s"OP_PUSH - invalid bytes length: ${bytes.length}")
  }

  def unsafe(bytes: ByteString): OP_PUSH = {
    assume(validate(bytes))
    new OP_PUSH(bytes) {}
  }

  private def safeDeserialize(bytes: ByteString,
                              length: Int): SerdeResult[(OP_PUSH, ByteString)] = {
    Instruction.safeTake(bytes, length).map {
      case (raw, rest) =>
        (OP_PUSH.unsafe(raw), rest)
    }
  }

  override def deserialize(input: ByteString): SerdeResult[(OP_PUSH, ByteString)] = {
    Instruction.safeHead(input).flatMap {
      case (code, rest) =>
        code match {
          case n if n >= 0x00 && n <= 0x06 => safeDeserialize(rest, 1 << Bits.toPosInt(n))
          case 0x07 =>
            Instruction.safeHead(rest).flatMap {
              case (_length, newRest) =>
                val length = Bits.toPosInt(_length)
                if (length > 0 && length <= 0xFF) safeDeserialize(newRest, length)
                else Left(SerdeError.validation(s"OP_PUSH - invalid bytes length $length"))
            }
          case _ => Left(SerdeError.validation(s"OP_PUSH - invalid code"))
        }
    }
  }
}

sealed abstract case class OP_DUP(index: Int) extends Instruction {
  override def runWith(state: RunState): RunResult[Unit] = {
    val stack = state.stack
    for {
      elem <- stack.peek(index)
      _    <- stack.push(elem)
    } yield ()
  }

  override def serialize(): ByteString = {
    index match {
      case n if n > 0 && n <= 15    => ByteString(0x0F + n)
      case n if n > 15 && n < 0x100 => ByteString(0x1F, n)
      case _                        => throw new RuntimeException(s"OP_PUSH - invalid index $index")
    }
  }
}

object OP_DUP extends InstructionCompanion with Registrable {
  override def register(): Unit = Instruction.register(0x10, 0x1F, this)

  @inline private def validate(index: Int): Boolean = index > 0 && index < 0x100

  def from(index: Int): Option[OP_DUP] = {
    if (validate(index)) Some(new OP_DUP(index) {}) else None
  }

  def unsafe(index: Int): OP_DUP = {
    assume(validate(index))
    new OP_DUP(index) {}
  }

  override def deserialize(input: ByteString): SerdeResult[(OP_DUP, ByteString)] = {
    Instruction.safeHead(input).flatMap {
      case (code, rest) =>
        code match {
          case n if n >= 0x10 && n < 0x1F => Right((unsafe(n - 0x0F), rest))
          case 0x1F =>
            Instruction.safeHead(rest).map {
              case (index, newRest) => (unsafe(Bits.toPosInt(index)), newRest)
            }
          case _ => Left(SerdeError.validation(s"OP_DUP - invalid code"))
        }
    }
  }
}

sealed abstract case class OP_SWAP(index: Int) extends Instruction {
  override def runWith(state: RunState): RunResult[Unit] = {
    state.stack.swap(index)
  }

  override def serialize(): ByteString = {
    index match {
      case n if n > 1 && n <= 16    => ByteString(0x1E + n)
      case n if n > 16 && n < 0x100 => ByteString(0x2F, n)
      case _                        => throw new RuntimeException(s"OP_SWAP - invalid index $index")
    }
  }
}

object OP_SWAP extends InstructionCompanion with Registrable {
  override def register(): Unit = Instruction.register(0x20, 0x2F, this)

  def validate(index: Int): Boolean = index > 1 && index < 0x100

  def from(index: Int): Option[OP_SWAP] = {
    if (validate(index)) Some(new OP_SWAP(index) {}) else None
  }

  def unsafe(index: Int): OP_SWAP = {
    assume(validate(index))
    new OP_SWAP(index) {}
  }

  override def deserialize(input: ByteString): SerdeResult[(OP_SWAP, ByteString)] = {
    Instruction.safeHead(input).flatMap {
      case (code, rest) =>
        code match {
          case n if 0x20 <= n && n < 0x2F => Right((unsafe(n - 0x1E), rest))
          case 0x2F =>
            Instruction.safeHead(rest).map {
              case (index, newRest) => (unsafe(Bits.toPosInt(index)), newRest)
            }
          case _ => Left(SerdeError.validation(s"OP_SWAP - invalid code"))
        }
    }
  }
}

sealed abstract case class OP_POP(total: Int) extends Instruction {
  override def runWith(state: RunState): RunResult[Unit] = {
    state.stack.remove(total)
  }

  override def serialize(): ByteString = {
    total match {
      case n if n >= 1 && n < 16     => ByteString(0x2F + n)
      case n if n >= 16 && n < 0x100 => ByteString(0x3F, n)
      case _                         => throw new RuntimeException(s"OP_POP - invalid total $total")
    }
  }
}

case object OP_POP extends InstructionCompanion with Registrable {
  override def register(): Unit = Instruction.register(0x30, 0x3F, this)

  def validate(index: Int): Boolean = index >= 1 && index < 0x100

  def from(index: Int): Option[OP_POP] = {
    if (validate(index)) Some(new OP_POP(index) {}) else None
  }

  def unsafe(index: Int): OP_POP = {
    assume(validate(index))
    new OP_POP(index) {}
  }

  override def deserialize(input: ByteString): SerdeResult[(Instruction, ByteString)] = {
    Instruction.safeHead(input).flatMap {
      case (code, rest) =>
        code match {
          case n if 0x30 <= n && n < 0x3F => Right((unsafe(n - 0x2F), rest))
          case 0x3F =>
            Instruction.safeHead(rest).map {
              case (index, newRest) => (unsafe(Bits.toPosInt(index)), newRest)
            }
          case _ => Left(SerdeError.validation(s"OP_POP - invalid code"))
        }
    }
  }
}

// BitwiseInstructions
case object OP_EQUALVERIFY extends SimpleInstruction with Registrable {
  def checkPoped(item0: ByteString, item1: ByteString): RunResult[Unit] = {
    if (item0 == item1) Right(()) else Left(VerificationFailed)
  }

  override def runWith(state: RunState): RunResult[Unit] = {
    val stack = state.stack
    for {
      item0 <- stack.pop()
      item1 <- stack.pop()
      _     <- checkPoped(item0, item1)
    } yield ()
  }

  override val code: Byte = 0x40

  override def register(): Unit = Instruction.register(code, this)
}

// Crypto Instructions
case object OP_KECCAK256 extends SimpleInstruction with Registrable {
  override def runWith(state: RunState): RunResult[Unit] = {
    val stack = state.stack
    for {
      bytes <- stack.pop()
      _     <- stack.push(Keccak256.hash(bytes).bytes)
    } yield ()
  }

  override val code: Byte = 0x50

  override def register(): Unit = Instruction.register(code, this)
}

case object OP_CHECKSIG extends SimpleInstruction with Registrable {
  override def runWith(state: RunState): RunResult[Unit] = {
    val rawData    = state.context.rawData
    val stack      = state.stack
    val signatures = state.signatures
    for {
      rawPublicKey <- stack.pop()
      signature    <- signatures.pop()
      publicKey    <- Instruction.decodePublicKey(rawPublicKey)
      _            <- Instruction.verify(rawData, signature, publicKey)
    } yield ()
  }

  override val code: Byte = 0x51

  override def register(): Unit = Instruction.register(code, this)
}
