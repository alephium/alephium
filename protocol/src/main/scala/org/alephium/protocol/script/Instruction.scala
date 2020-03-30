package org.alephium.protocol.script

import scala.annotation.tailrec

import akka.util.ByteString

import org.alephium.crypto.{ED25519, ED25519PublicKey, ED25519Signature, Keccak256}
import org.alephium.macros.EnumerationMacros
import org.alephium.serde._
import org.alephium.util.{AVector, Bits, EitherF}

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

  def serializeScript(input: AVector[Instruction]): ByteString = {
    input.fold(ByteString.empty)(_ ++ _.serialize())
  }

  def deserializeScript(input: ByteString): RunResult[AVector[Instruction]] = {
    deserializeScript(input, AVector.empty)
  }

  @tailrec
  private def deserializeScript(input: ByteString,
                                acc: AVector[Instruction]): RunResult[AVector[Instruction]] = {
    if (input.isEmpty) Right(acc)
    else {
      serde._deserialize(input) match {
        case Right((instruction, rest)) => deserializeScript(rest, acc :+ instruction)
        case Left(error)                => Left(InvalidScript(error.getMessage))
      }
    }
  }

  private[script] def decodePublicKey(bytes: ByteString): RunResult[ED25519PublicKey] = {
    ED25519PublicKey.from(bytes).toRight(InvalidPublicKey)
  }

  private[script] def verify(rawData: ByteString,
                             signature: ED25519Signature,
                             publicKey: ED25519PublicKey): RunResult[Unit] = {
    if (ED25519.verify(rawData, signature, publicKey)) Right(()) else Left(VerificationFailed)
  }

  private[script] def verify(rawData: ByteString,
                             signatures: AVector[ED25519Signature],
                             publicKeys: AVector[ED25519PublicKey]): RunResult[Unit] = {
    assume(signatures.length == publicKeys.length)
    EitherF.foreachTry(signatures.indices) { index =>
      val signature = signatures(index)
      val publicKey = publicKeys(index)
      verify(rawData, signature, publicKey)
    }
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

  val True: ByteString  = ByteString(1)
  val False: ByteString = ByteString(0)

  private[script] def bool(raw: ByteString): RunResult[Boolean] = {
    if (raw == Instruction.True) Right(true)
    else if (raw == Instruction.False) Right(false)
    else Left(InvalidBoolean)
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

  def register(): Unit = Instruction.register(code, this)

  override def serialize(): ByteString = ByteString(code)

  override def deserialize(input: ByteString): SerdeResult[(Instruction, ByteString)] = {
    Instruction.safeHead(input).flatMap {
      case (codeRaw, rest) =>
        if (codeRaw == code) Right((this, rest))
        else Left(SerdeError.validation(s"SimpleInstruction - invalid code"))
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

  @inline private def validate(bytes: ByteString): Boolean = validateSize(bytes.size)
  @inline private def validateSize(size: Int): Boolean     = size > 0 && size < 0x100

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
                if (validateSize(length)) safeDeserialize(newRest, length)
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

  def from(index: Int): Either[String, OP_DUP] = {
    if (validate(index)) Right(new OP_DUP(index) {})
    else Left(s"OP_DUP - invalid index $index")
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

  @inline private def validate(index: Int): Boolean = index > 1 && index < 0x100

  def from(index: Int): Either[String, OP_SWAP] = {
    if (validate(index)) Right(new OP_SWAP(index) {})
    else Left(s"OP_SWAP: invalid index $index")
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

  private def validate(index: Int): Boolean = index >= 1 && index < 0x100

  def from(index: Int): Either[String, OP_POP] = {
    if (validate(index)) Right(new OP_POP(index) {})
    else Left(s"OP_POP - invalid index $index")
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

object Arithmetic {
  val maxLen: Int = 32

  private[script] def validate(bs: ByteString): RunResult[BigInt] = {
    if (bs.length <= maxLen) Right(BigInt(bs.toArray))
    else Left(IntegerOverFlow)
  }

  private[script] def validate(n: BigInt): RunResult[BigInt] = {
    val byteLen = n.bitLength / 8 + 1
    if (byteLen <= maxLen) Right(n)
    else Left(IntegerOverFlow)
  }
}

// Arithmetic Operations
sealed trait BinaryArithmetic extends SimpleInstruction {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt]

  private def checkedOp(x: BigInt, y: BigInt): RunResult[ByteString] = {
    for {
      out       <- op(x, y)
      validated <- Arithmetic.validate(out)
    } yield ByteString(validated.toByteArray)
  }

  override def runWith(state: RunState): RunResult[Unit] = {
    val stack = state.stack
    for {
      x   <- stack.pop().flatMap(Arithmetic.validate)
      y   <- stack.pop().flatMap(Arithmetic.validate)
      out <- checkedOp(x, y)
      _   <- stack.push(out)
    } yield ()
  }
}

case object OP_ADD extends BinaryArithmetic with Registrable {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt] = {
    Right(x + y)
  }

  override val code: Byte = 0x40
}

case object OP_SUB extends BinaryArithmetic with Registrable {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt] = {
    Right(x - y)
  }

  override val code: Byte = 0x41
}

case object OP_MUL extends BinaryArithmetic with Registrable {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt] = {
    Right(x * y)
  }

  override val code: Byte = 0x42
}

case object OP_DIV extends BinaryArithmetic with Registrable {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt] = {
    try Right(x / y)
    catch {
      case e: ArithmeticException => Left(ArithmeticError(e.getMessage))
    }
  }

  override val code: Byte = 0x43
}

// Flow Control
case object OP_IF extends SimpleInstruction with Registrable {
  override def runWith(state: RunState): RunResult[Unit] = {
    val stack = state.stack
    for {
      raw       <- stack.pop()
      condition <- Instruction.bool(raw)
      _         <- state.runIf(condition)
    } yield ()
  }

  override val code: Byte = 0x50
}

case object OP_ELSE extends SimpleInstruction with Registrable {
  override def runWith(state: RunState): RunResult[Unit] = Left(IncompleteIfScript)

  override val code: Byte = 0x51
}

case object OP_ENDIF extends SimpleInstruction with Registrable {
  override def runWith(state: RunState): RunResult[Unit] = Left(IncompleteIfScript)

  override val code: Byte = 0x52
}

case object OP_VERIFY extends SimpleInstruction with Registrable {
  override def runWith(state: RunState): RunResult[Unit] = {
    for {
      raw       <- state.stack.pop()
      condition <- Instruction.bool(raw)
      _         <- if (condition) Right(()) else Left(VerificationFailed)
    } yield ()
  }

  override val code: Byte = 0x53
}

// Logic Instructions
case object OP_EQ extends BinaryArithmetic with Registrable {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt] = {
    if (x == y) Right(BigInt(1)) else Right(BigInt(0))
  }

  override val code: Byte = 0x60
}

case object OP_NE extends BinaryArithmetic with Registrable {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt] = {
    if (x != y) Right(BigInt(1)) else Right(BigInt(0))
  }

  override val code: Byte = 0x61
}

case object OP_LT extends BinaryArithmetic with Registrable {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt] = {
    if (x < y) Right(BigInt(1)) else Right(BigInt(0))
  }

  override val code: Byte = 0x62
}

case object OP_GT extends BinaryArithmetic with Registrable {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt] = {
    if (x > y) Right(BigInt(1)) else Right(BigInt(0))
  }

  override val code: Byte = 0x63
}

case object OP_LE extends BinaryArithmetic with Registrable {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt] = {
    if (x <= y) Right(BigInt(1)) else Right(BigInt(0))
  }

  override val code: Byte = 0x64
}

case object OP_GE extends BinaryArithmetic with Registrable {
  protected def op(x: BigInt, y: BigInt): RunResult[BigInt] = {
    if (x >= y) Right(BigInt(1)) else Right(BigInt(0))
  }

  override val code: Byte = 0x65
}

case object OP_EQUAL extends SimpleInstruction with Registrable {
  override def runWith(state: RunState): RunResult[Unit] = {
    val stack = state.stack
    for {
      item0 <- stack.pop()
      item1 <- stack.pop()
      _     <- if (item0 == item1) stack.push(Instruction.True) else stack.push(Instruction.False)
    } yield {}
  }

  override val code: Byte = 0x66
}

case object OP_EQUALVERIFY extends SimpleInstruction with Registrable {
  private def checkPoped(item0: ByteString, item1: ByteString): RunResult[Unit] = {
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

  override val code: Byte = 0x67
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

  override val code: Byte = 0x70
}

case object OP_CHECKSIGVERIFY extends SimpleInstruction with Registrable {
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

  override val code: Byte = 0x71
}

case object OP_CHECKMULTISIGVERIFY extends SimpleInstruction with Registrable {
  private def validate(nRaw: ByteString): RunResult[Int] = {
    if (nRaw.length == 1) {
      val n = Bits.toPosInt(nRaw(0))
      if (n > 0) Right(n) else Left(InvalidParameters)
    } else Left(InvalidParameters)
  }

  override def runWith(state: RunState): RunResult[Unit] = {
    val rawData = state.context.rawData
    val stack   = state.stack
    for {
      sigNum     <- stack.pop().flatMap(validate)
      publicKeys <- stack.pop(sigNum).flatMap(_.mapE(Instruction.decodePublicKey))
      signatures <- state.signatures.pop(sigNum)
      _          <- Instruction.verify(rawData, signatures, publicKeys)
    } yield ()
  }

  override val code: Byte = 0x72
}

// Script Instructions

sealed abstract case class OP_SCRIPTKECCAK256(hash: Keccak256) extends Instruction {
  private def validateHash(scriptRaw: ByteString): RunResult[Unit] = {
    if (Keccak256.hash(scriptRaw) == hash) Right(())
    else Left(InvalidScriptHash)
  }

  override def runWith(state: RunState): RunResult[Unit] = {
    for {
      scriptRaw <- state.stack.pop()
      _         <- validateHash(scriptRaw)
      script    <- Instruction.deserializeScript(scriptRaw)
      _         <- state.run(script)
    } yield ()
  }

  override def serialize(): ByteString = {
    ByteString(OP_SCRIPTKECCAK256.code) ++ hash.bytes
  }
}

object OP_SCRIPTKECCAK256 extends InstructionCompanion with Registrable {
  val code: Byte = 0x80.toByte

  override def register(): Unit = Instruction.register(code, this)

  def from(hash: Keccak256): OP_SCRIPTKECCAK256 = new OP_SCRIPTKECCAK256(hash) {}

  def unsafe(rawHash: ByteString): OP_SCRIPTKECCAK256 = {
    new OP_SCRIPTKECCAK256(Keccak256.unsafe(rawHash)) {}
  }

  override def deserialize(input: ByteString): SerdeResult[(Instruction, ByteString)] = {
    Instruction.safeHead(input).flatMap {
      case (codeRaw, rest) =>
        if (codeRaw == code) {
          Instruction.safeTake(rest, Keccak256.length).map {
            case (rawHash, newRest) => OP_SCRIPTKECCAK256.unsafe(rawHash) -> newRest
          }
        } else Left(SerdeError.validation("OP_SCRIPTKECCAK256 - invalid code"))
    }
  }
}
