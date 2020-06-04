package org.alephium.protocol.vm

import scala.collection.immutable.ArraySeq

import akka.util.ByteString

import org.alephium.serde.{Serde, SerdeError, SerdeResult}
import org.alephium.util
import org.alephium.util.{Bytes, Collection}

sealed trait Instr[-Ctx <: Context] {
  def serialize(): ByteString

  def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit]
}

object Instr {
  implicit val statelessSerde: Serde[Instr[StatelessContext]] = new Serde[Instr[StatelessContext]] {
    override def serialize(input: Instr[StatelessContext]): ByteString = input.serialize()

    override def _deserialize(
        input: ByteString): SerdeResult[(Instr[StatelessContext], ByteString)] = {
      for {
        code <- input.headOption.toRight(SerdeError.notEnoughBytes(1, 0))
        instrCompanion <- getStatelessCompanion(code).toRight(
          SerdeError.validation(s"Instruction - invalid code $code"))
        output <- instrCompanion.deserialize[StatelessContext](input.tail)
      } yield output
    }
  }
  implicit val statefulSerde: Serde[Instr[StatefulContext]] = new Serde[Instr[StatefulContext]] {
    override def serialize(input: Instr[StatefulContext]): ByteString = input.serialize()

    override def _deserialize(
        input: ByteString): SerdeResult[(Instr[StatefulContext], ByteString)] = {
      for {
        code <- input.headOption.toRight(SerdeError.notEnoughBytes(1, 0))
        instrCompanion <- getStatefulCompanion(code).toRight(
          SerdeError.validation(s"Instruction - invalid code $code"))
        output <- instrCompanion.deserialize[StatefulContext](input)
      } yield output
    }
  }

  def getStatelessCompanion(byte: Byte): Option[InstrCompanion[StatelessContext]] = {
    Collection.get(statelessInstrs, Bytes.toPosInt(byte))
  }

  def getStatefulCompanion(byte: Byte): Option[InstrCompanion[StatefulContext]] = {
    Collection.get(statefulInstrs, Bytes.toPosInt(byte))
  }

  // format: off
  val statelessInstrs: ArraySeq[InstrCompanion[StatelessContext]] = ArraySeq(
    BoolConstTrue, BoolConstFalse,
    I32ConstN1, I32Const0, I32Const1, I32Const2, I32Const3, I32Const4, I32Const5,
                U32Const0, U32Const1, U32Const2, U32Const3, U32Const4, U32Const5,
    I64ConstN1, I64Const0, I64Const1, I64Const2, I64Const3, I64Const4, I64Const5,
                U64Const0, U64Const1, U64Const2, U64Const3, U64Const4, U64Const5,
    I256ConstN1, I256Const0, I256Const1, I256Const2, I256Const3, I256Const4, I256Const5,
                 U256Const0, U256Const1, U256Const2, U256Const3, U256Const4, U256Const5,
    LoadLocal, StoreLocal, LoadField, StoreField,
    Pop, Pop2, Dup, Dup2, Swap,
    U64Add, U64Sub, U64Mul, U64Div, U64Mod,
  )
  val statefulInstrs: ArraySeq[InstrCompanion[StatefulContext]]   = statelessInstrs
  // format: on

  val toCode: Map[InstrCompanion[StatefulContext], Int] = statefulInstrs.zipWithIndex.toMap
}

trait StatelessInstr extends Instr[StatelessContext]
trait StatefulInstr  extends Instr[StatefulContext]

sealed trait InstrCompanion[-Ctx <: Context] {
  def deserialize[C <: Ctx](input: ByteString): SerdeResult[(Instr[C], ByteString)]
}

sealed trait InstrCompanion1 extends InstrCompanion[StatelessContext] {
  lazy val code: Byte = Instr.toCode(this).toByte

  def apply(index: Int): Instr[StatelessContext]

  @inline def from[C <: StatelessContext](byte: Byte): Instr[C] = apply(Bytes.toPosInt(byte))

  override def deserialize[C <: StatelessContext](
      input: ByteString): SerdeResult[(Instr[C], ByteString)] = {
    input.headOption
      .map(byte => (from(byte), input.tail))
      .toRight(SerdeError.notEnoughBytes(1, 0))
  }
}

trait SimpleInstr extends InstrCompanion[StatelessContext] with Instr[StatelessContext] {
  lazy val code: Byte = Instr.toCode(this).toByte

  override def serialize(): ByteString = ByteString(code)

  override def deserialize[C <: StatelessContext](
      input: ByteString): SerdeResult[(Instr[C], ByteString)] =
    Right((this, input))
}

trait OperandStackInstr extends StatelessInstr

trait ConstInstr extends OperandStackInstr with SimpleInstr {
  def const: Val

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.push(const)
  }
}

object BoolConstTrue  extends ConstInstr { val const: Val = Val.Bool(true) }
object BoolConstFalse extends ConstInstr { val const: Val = Val.Bool(false) }

object I32ConstN1 extends ConstInstr { val const: Val = Val.I32(util.I32.NegOne) }
object I32Const0  extends ConstInstr { val const: Val = Val.I32(util.I32.Zero) }
object I32Const1  extends ConstInstr { val const: Val = Val.I32(util.I32.One) }
object I32Const2  extends ConstInstr { val const: Val = Val.I32(util.I32.Two) }
object I32Const3  extends ConstInstr { val const: Val = Val.I32(util.I32.unsafe(3)) }
object I32Const4  extends ConstInstr { val const: Val = Val.I32(util.I32.unsafe(4)) }
object I32Const5  extends ConstInstr { val const: Val = Val.I32(util.I32.unsafe(5)) }

object U32Const0 extends ConstInstr { val const: Val = Val.U32(util.U32.Zero) }
object U32Const1 extends ConstInstr { val const: Val = Val.U32(util.U32.One) }
object U32Const2 extends ConstInstr { val const: Val = Val.U32(util.U32.Two) }
object U32Const3 extends ConstInstr { val const: Val = Val.U32(util.U32.unsafe(3)) }
object U32Const4 extends ConstInstr { val const: Val = Val.U32(util.U32.unsafe(4)) }
object U32Const5 extends ConstInstr { val const: Val = Val.U32(util.U32.unsafe(5)) }

object I64ConstN1 extends ConstInstr { val const: Val = Val.I64(util.I64.NegOne) }
object I64Const0  extends ConstInstr { val const: Val = Val.I64(util.I64.Zero) }
object I64Const1  extends ConstInstr { val const: Val = Val.I64(util.I64.One) }
object I64Const2  extends ConstInstr { val const: Val = Val.I64(util.I64.Two) }
object I64Const3  extends ConstInstr { val const: Val = Val.I64(util.I64.unsafe(3)) }
object I64Const4  extends ConstInstr { val const: Val = Val.I64(util.I64.unsafe(4)) }
object I64Const5  extends ConstInstr { val const: Val = Val.I64(util.I64.unsafe(5)) }

object U64Const0 extends ConstInstr { val const: Val = Val.U64(util.U64.Zero) }
object U64Const1 extends ConstInstr { val const: Val = Val.U64(util.U64.One) }
object U64Const2 extends ConstInstr { val const: Val = Val.U64(util.U64.Two) }
object U64Const3 extends ConstInstr { val const: Val = Val.U64(util.U64.unsafe(3)) }
object U64Const4 extends ConstInstr { val const: Val = Val.U64(util.U64.unsafe(4)) }
object U64Const5 extends ConstInstr { val const: Val = Val.U64(util.U64.unsafe(5)) }

object I256ConstN1 extends ConstInstr { val const: Val = Val.I256(util.I256.NegOne) }
object I256Const0  extends ConstInstr { val const: Val = Val.I256(util.I256.Zero) }
object I256Const1  extends ConstInstr { val const: Val = Val.I256(util.I256.One) }
object I256Const2  extends ConstInstr { val const: Val = Val.I256(util.I256.Two) }
object I256Const3  extends ConstInstr { val const: Val = Val.I256(util.I256.from(3L)) }
object I256Const4  extends ConstInstr { val const: Val = Val.I256(util.I256.from(4L)) }
object I256Const5  extends ConstInstr { val const: Val = Val.I256(util.I256.from(5L)) }

object U256Const0 extends ConstInstr { val const: Val = Val.U256(util.U256.Zero) }
object U256Const1 extends ConstInstr { val const: Val = Val.U256(util.U256.One) }
object U256Const2 extends ConstInstr { val const: Val = Val.U256(util.U256.Two) }
object U256Const3 extends ConstInstr { val const: Val = Val.U256(util.U256.unsafe(3L)) }
object U256Const4 extends ConstInstr { val const: Val = Val.U256(util.U256.unsafe(4L)) }
object U256Const5 extends ConstInstr { val const: Val = Val.U256(util.U256.unsafe(5L)) }

// Note: 0 <= index <= 0xFF
case class LoadLocal(index: Int) extends OperandStackInstr {
  override def serialize(): ByteString = ByteString(LoadLocal.code, index.toByte)
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getLocal(index)
      _ <- frame.push(v)
    } yield ()
  }
}
object LoadLocal extends InstrCompanion1
case class StoreLocal(index: Int) extends OperandStackInstr {
  override def serialize(): ByteString = ByteString(StoreLocal.code, index.toByte)
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.pop()
      _ <- frame.setLocal(index, v)
    } yield ()
  }
}
object StoreLocal extends InstrCompanion1
case class LoadField(index: Int) extends OperandStackInstr {
  override def serialize(): ByteString = ByteString(LoadField.code, index.toByte)
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getField(index)
      _ <- frame.push(v)
    } yield ()
  }
}
object LoadField extends InstrCompanion1
case class StoreField(index: Int) extends OperandStackInstr {
  override def serialize(): ByteString = ByteString(StoreField.code, index.toByte)
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.pop()
      _ <- frame.setField(index, v)
    } yield ()
  }
}
object StoreField extends InstrCompanion1

trait PureStackInstr extends OperandStackInstr with SimpleInstr

case object Pop extends PureStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.remove(1)
  }
}
case object Pop2 extends PureStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.remove(2)
  }
}
object Dup extends PureStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.dup(1)
  }
}
object Dup2 extends PureStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.dup(2)
  }
}
object Swap extends PureStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.swap(2)
  }
}

trait ArithmeticInstr extends SimpleInstr

trait BinaryArithmeticInstr extends ArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val]

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      x   <- frame.pop()
      y   <- frame.pop()
      out <- op(x, y)
      _   <- frame.push(out)
    } yield ()
  }
}
object BinaryArithmeticInstr {
  def error(a: Val, b: Val, op: ArithmeticInstr): ArithmeticError = {
    ArithmeticError(s"Arithmetic error: $op($a, $b)")
  }

  @inline def u64SafeOp(
      instr: ArithmeticInstr,
      op: (util.U64, util.U64) => Option[util.U64]
  )(x: Val, y: Val): ExeResult[Val] =
    (x, y) match {
      case (a: Val.U64, b: Val.U64) =>
        op(a.v, b.v).map(Val.U64.apply).toRight(BinaryArithmeticInstr.error(a, b, instr))
      case _ => Left(BinaryArithmeticInstr.error(x, y, instr))
    }
}
object U64Add extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64SafeOp(this, _.add(_))(x, y)
}
object U64Sub extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64SafeOp(this, _.sub(_))(x, y)
}
object U64Mul extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64SafeOp(this, _.mul(_))(x, y)
}
object U64Div extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64SafeOp(this, _.div(_))(x, y)
}
object U64Mod extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64SafeOp(this, _.mod(_))(x, y)
}
//object U64Neg extends ArithmeticInstr

trait ConversionInstr extends StatelessInstr
trait ToI32           extends ConversionInstr
trait ToU32           extends ConversionInstr
trait ToI64           extends ConversionInstr
trait ToU64           extends ConversionInstr
trait ToI256          extends ConversionInstr
trait ToU256          extends ConversionInstr

trait ObjectInstr   extends StatelessInstr
trait NewBooleanVec extends ObjectInstr
trait NewByteVec    extends ObjectInstr
trait NewI32Vec     extends ObjectInstr
trait NewU32Vec     extends ObjectInstr
trait NewI64Vec     extends ObjectInstr
trait NewU64Vec     extends ObjectInstr
trait NewI256Vec    extends ObjectInstr
trait NewU256Vec    extends ObjectInstr
trait NewByte256Vec extends ObjectInstr

trait ControlInstr extends StatelessInstr
trait If           extends ControlInstr
trait IfElse       extends ControlInstr
trait DoWhile      extends ControlInstr

trait InvokeInstr     extends StatelessInstr
trait InvokeInternal  extends InvokeInstr
trait InvokerExternal extends InvokeInstr

trait CryptoInstr   extends StatelessInstr
trait HashAlg       extends CryptoInstr
trait Signature     extends CryptoInstr
trait EllipticCurve extends CryptoInstr

trait ContextInstr    extends StatelessInstr
trait BlockInfo       extends ContextInstr
trait TransactionInfo extends ContextInstr
trait InputInfo       extends ContextInstr
trait OutputInfo      extends ContextInstr

trait ContractInfo extends StatefulInstr
