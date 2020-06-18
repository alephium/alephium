package org.alephium.protocol.vm

import scala.annotation.switch
import scala.collection.immutable.ArraySeq

import akka.util.ByteString

import org.alephium.crypto.{ED25519, ED25519PublicKey, Keccak256}
import org.alephium.serde._
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
    I64Const0, I64Const1, I64Const2, I64Const3, I64Const4, I64Const5, I64ConstN1,
    U64Const0, U64Const1, U64Const2, U64Const3, U64Const4, U64Const5,
    I256Const0, I256Const1, I256Const2, I256Const3, I256Const4, I256Const5, I256ConstN1,
    U256Const0, U256Const1, U256Const2, U256Const3, U256Const4, U256Const5,
    I64Const, U64Const, I256Const, U256Const,
    LoadLocal, StoreLocal, LoadField, StoreField,
    Pop, Pop2, Dup, Dup2, Swap,
    I64Add,  I64Sub,  I64Mul,  I64Div,  I64Mod,  EqI64,  NeI64,  LtI64,  LeI64,  GtI64,  GeI64,
    U64Add,  U64Sub,  U64Mul,  U64Div,  U64Mod,  EqU64,  NeU64,  LtU64,  LeU64,  GtU64,  GeU64,
    I256Add, I256Sub, I256Mul, I256Div, I256Mod, EqI256, NeI256, LtI256, LeI256, GtI256, GeI256,
    U256Add, U256Sub, U256Mul, U256Div, U256Mod, EqU256, NeU256, LtU256, LeU256, GtU256, GeU256,
    NotBool, AndBool, OrBool,
                ByteToI64, ByteToU64, ByteToI256, ByteToU256,
    I64ToByte,             I64ToU64,  I64ToI256,  I64ToU256,
    U64ToByte,  U64ToI64,             U64ToI256,  U64ToU256,
    I256ToByte, I256ToI64, I256ToU64,             I256ToU256,
    U256ToByte, U256ToI64, U256ToU64, U256ToI256,
    Offset, IfTrue, IfFalse, IfAnd, IfOr, IfNotAnd, IfNotOr, // TODO: support long branches, 256 instrs rn
    IfEqI64, IfNeI64, IfLtI64, IfLeI64, IfGtI64, IfGeI64,
    IfEqU64, IfNeU64, IfLtU64, IfLeU64, IfGtU64, IfGeU64,
    IfEqI256, IfNeI256, IfLtI256, IfLeI256, IfGtI256, IfGeI256,
    IfEqU256, IfNeU256, IfLtU256, IfLeU256, IfGtU256, IfGeU256,
    CallLocal, Return,
    CheckEqBool, CheckEqByte, CheckEqI64, CheckEqU64, CheckEqI256, CheckEqU256, CheckEqByte32,
    CheckEqBoolVec, CheckEqByteVec, CheckEqI64Vec, CheckEqU64Vec, CheckEqI256Vec, CheckEqU256Vec, CheckEqByte32Vec,
    Keccak256Byte32, Keccak256ByteVec, CheckSignature
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

sealed abstract class InstrCompanion1[T: Serde] extends InstrCompanion[StatelessContext] {
  lazy val code: Byte = Instr.toCode(this).toByte

  def apply(t: T): Instr[StatelessContext]

  @inline def from[C <: StatelessContext](t: T): Instr[C] = apply(t)

  override def deserialize[C <: StatelessContext](
      input: ByteString): SerdeResult[(Instr[C], ByteString)] = {
    serdeImpl[T]._deserialize(input).map {
      case (t, rest) => (from(t), rest)
    }
  }
}

trait InstrCompanion0 extends InstrCompanion[StatelessContext] with Instr[StatelessContext] {
  lazy val code: Byte = Instr.toCode(this).toByte

  override def serialize(): ByteString = ByteString(code)

  override def deserialize[C <: StatelessContext](
      input: ByteString): SerdeResult[(Instr[C], ByteString)] =
    Right((this, input))
}

trait OperandStackInstr extends StatelessInstr

trait ConstInstr extends OperandStackInstr
object ConstInstr {
  def i64(v: Val.I64): ConstInstr = {
    // TODO: use @switch annotation
    (v.v.v) match {
      case -1 => I64ConstN1
      case 0  => I64Const0
      case 1  => I64Const1
      case 2  => I64Const2
      case 3  => I64Const3
      case 4  => I64Const4
      case 5  => I64Const5
      case _  => I64Const(v)
    }
  }

  def u64(v: Val.U64): ConstInstr = {
    // TODO: use @switch annotation
    (v.v.v) match {
      case 0 => U64Const0
      case 1 => U64Const1
      case 2 => U64Const2
      case 3 => U64Const3
      case 4 => U64Const4
      case 5 => U64Const5
      case _ => U64Const(v)
    }
  }

  def i256(v: Val.I256): ConstInstr = {
    val bi = v.v.v
    if (bi.bitLength() <= 8) {
      (bi.intValue(): @switch) match {
        case -1 => I256ConstN1
        case 0  => I256Const0
        case 1  => I256Const1
        case 2  => I256Const2
        case 3  => I256Const3
        case 4  => I256Const4
        case 5  => I256Const5
        case _  => I256Const(v)
      }
    } else {
      I256Const(v)
    }
  }

  def u256(v: Val.U256): ConstInstr = {
    val bi = v.v.v
    if (bi.bitLength() <= 8) {
      (bi.intValue(): @switch) match {
        case 0 => U256Const0
        case 1 => U256Const1
        case 2 => U256Const2
        case 3 => U256Const3
        case 4 => U256Const4
        case 5 => U256Const5
        case _ => U256Const(v)
      }
    } else {
      U256Const(v)
    }
  }
}

trait ConstInstr0 extends ConstInstr with InstrCompanion0 {
  def const: Val

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.push(const)
  }
}

abstract class ConstInstr1[T <: Val] extends ConstInstr {
  def n: T

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.push(n)
  }
}

object BoolConstTrue  extends ConstInstr0 { val const: Val = Val.Bool(true) }
object BoolConstFalse extends ConstInstr0 { val const: Val = Val.Bool(false) }

object I64ConstN1 extends ConstInstr0 { val const: Val = Val.I64(util.I64.NegOne) }
object I64Const0  extends ConstInstr0 { val const: Val = Val.I64(util.I64.Zero) }
object I64Const1  extends ConstInstr0 { val const: Val = Val.I64(util.I64.One) }
object I64Const2  extends ConstInstr0 { val const: Val = Val.I64(util.I64.Two) }
object I64Const3  extends ConstInstr0 { val const: Val = Val.I64(util.I64.from(3)) }
object I64Const4  extends ConstInstr0 { val const: Val = Val.I64(util.I64.from(4)) }
object I64Const5  extends ConstInstr0 { val const: Val = Val.I64(util.I64.from(5)) }

object U64Const0 extends ConstInstr0 { val const: Val = Val.U64(util.U64.Zero) }
object U64Const1 extends ConstInstr0 { val const: Val = Val.U64(util.U64.One) }
object U64Const2 extends ConstInstr0 { val const: Val = Val.U64(util.U64.Two) }
object U64Const3 extends ConstInstr0 { val const: Val = Val.U64(util.U64.unsafe(3)) }
object U64Const4 extends ConstInstr0 { val const: Val = Val.U64(util.U64.unsafe(4)) }
object U64Const5 extends ConstInstr0 { val const: Val = Val.U64(util.U64.unsafe(5)) }

object I256ConstN1 extends ConstInstr0 { val const: Val = Val.I256(util.I256.NegOne) }
object I256Const0  extends ConstInstr0 { val const: Val = Val.I256(util.I256.Zero) }
object I256Const1  extends ConstInstr0 { val const: Val = Val.I256(util.I256.One) }
object I256Const2  extends ConstInstr0 { val const: Val = Val.I256(util.I256.Two) }
object I256Const3  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(3L)) }
object I256Const4  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(4L)) }
object I256Const5  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(5L)) }

object U256Const0 extends ConstInstr0 { val const: Val = Val.U256(util.U256.Zero) }
object U256Const1 extends ConstInstr0 { val const: Val = Val.U256(util.U256.One) }
object U256Const2 extends ConstInstr0 { val const: Val = Val.U256(util.U256.Two) }
object U256Const3 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(3L)) }
object U256Const4 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(4L)) }
object U256Const5 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(5L)) }

final case class I64Const(n: Val.I64) extends ConstInstr1[Val.I64] {
  override def serialize(): ByteString =
    ByteString(I64Const.code) ++ serdeImpl[util.I64].serialize(n.v)
}
object I64Const extends InstrCompanion1[Val.I64]
final case class U64Const(n: Val.U64) extends ConstInstr1[Val.U64] {
  override def serialize(): ByteString =
    ByteString(U64Const.code) ++ serdeImpl[util.U64].serialize(n.v)
}
object U64Const extends InstrCompanion1[Val.U64]
final case class I256Const(n: Val.I256) extends ConstInstr1[Val.I256] {
  override def serialize(): ByteString =
    ByteString(I256Const.code) ++ serdeImpl[util.I256].serialize(n.v)
}
object I256Const extends InstrCompanion1[Val.I256]
final case class U256Const(n: Val.U256) extends ConstInstr1[Val.U256] {
  override def serialize(): ByteString =
    ByteString(U256Const.code) ++ serdeImpl[util.U256].serialize(n.v)
}
object U256Const extends InstrCompanion1[Val.U256]

// Note: 0 <= index <= 0xFF
final case class LoadLocal(index: Byte) extends OperandStackInstr {
  override def serialize(): ByteString = ByteString(LoadLocal.code, index)
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getLocal(Bytes.toPosInt(index))
      _ <- frame.push(v)
    } yield ()
  }
}
object LoadLocal extends InstrCompanion1[Byte]
final case class StoreLocal(index: Byte) extends OperandStackInstr {
  override def serialize(): ByteString = ByteString(StoreLocal.code, index)
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.pop()
      _ <- frame.setLocal(Bytes.toPosInt(index), v)
    } yield ()
  }
}
object StoreLocal extends InstrCompanion1[Byte]
final case class LoadField(index: Byte) extends OperandStackInstr {
  override def serialize(): ByteString = ByteString(LoadField.code, index)
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getField(Bytes.toPosInt(index))
      _ <- frame.push(v)
    } yield ()
  }
}
object LoadField extends InstrCompanion1[Byte]
final case class StoreField(index: Byte) extends OperandStackInstr {
  override def serialize(): ByteString = ByteString(StoreField.code, index)
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.pop()
      _ <- frame.setField(Bytes.toPosInt(index), v)
    } yield ()
  }
}
object StoreField extends InstrCompanion1[Byte]

trait PureStackInstr extends OperandStackInstr with InstrCompanion0

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

trait ArithmeticInstr extends InstrCompanion0

trait BinaryArithmeticInstr extends ArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val]

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value2 <- frame.pop()
      value1 <- frame.pop()
      out    <- op(value1, value2)
      _      <- frame.push(out)
    } yield ()
  }
}
object BinaryArithmeticInstr {
  def error(a: Val, b: Val, op: ArithmeticInstr): ArithmeticError = {
    ArithmeticError(s"Arithmetic error: $op($a, $b)")
  }

  @inline def i64SafeOp(
      instr: ArithmeticInstr,
      op: (util.I64, util.I64) => Option[util.I64]
  )(x: Val, y: Val): ExeResult[Val.I64] =
    (x, y) match {
      case (a: Val.I64, b: Val.I64) =>
        op(a.v, b.v).map(Val.I64.apply).toRight(BinaryArithmeticInstr.error(a, b, instr))
      case _ => Left(BinaryArithmeticInstr.error(x, y, instr))
    }

  @inline def u64SafeOp(
      instr: ArithmeticInstr,
      op: (util.U64, util.U64) => Option[util.U64]
  )(x: Val, y: Val): ExeResult[Val.U64] =
    (x, y) match {
      case (a: Val.U64, b: Val.U64) =>
        op(a.v, b.v).map(Val.U64.apply).toRight(BinaryArithmeticInstr.error(a, b, instr))
      case _ => Left(BinaryArithmeticInstr.error(x, y, instr))
    }

  @inline def i256SafeOp(
      instr: ArithmeticInstr,
      op: (util.I256, util.I256) => Option[util.I256]
  )(x: Val, y: Val): ExeResult[Val.I256] =
    (x, y) match {
      case (a: Val.I256, b: Val.I256) =>
        op(a.v, b.v).map(Val.I256.apply).toRight(BinaryArithmeticInstr.error(a, b, instr))
      case _ => Left(BinaryArithmeticInstr.error(x, y, instr))
    }

  @inline def u256SafeOp(
      instr: ArithmeticInstr,
      op: (util.U256, util.U256) => Option[util.U256]
  )(x: Val, y: Val): ExeResult[Val.U256] =
    (x, y) match {
      case (a: Val.U256, b: Val.U256) =>
        op(a.v, b.v).map(Val.U256.apply).toRight(BinaryArithmeticInstr.error(a, b, instr))
      case _ => Left(BinaryArithmeticInstr.error(x, y, instr))
    }

  @inline def i64Comp(
      instr: ArithmeticInstr,
      op: (util.I64, util.I64) => Boolean
  )(x: Val, y: Val): ExeResult[Val.Bool] =
    (x, y) match {
      case (a: Val.I64, b: Val.I64) => Right(Val.Bool(op(a.v, b.v)))
      case _                        => Left(BinaryArithmeticInstr.error(x, y, instr))
    }

  @inline def u64Comp(
      instr: ArithmeticInstr,
      op: (util.U64, util.U64) => Boolean
  )(x: Val, y: Val): ExeResult[Val.Bool] =
    (x, y) match {
      case (a: Val.U64, b: Val.U64) => Right(Val.Bool(op(a.v, b.v)))
      case _                        => Left(BinaryArithmeticInstr.error(x, y, instr))
    }

  @inline def i256Comp(
      instr: ArithmeticInstr,
      op: (util.I256, util.I256) => Boolean
  )(x: Val, y: Val): ExeResult[Val.Bool] =
    (x, y) match {
      case (a: Val.I256, b: Val.I256) => Right(Val.Bool(op(a.v, b.v)))
      case _                          => Left(BinaryArithmeticInstr.error(x, y, instr))
    }

  @inline def u256Comp(
      instr: ArithmeticInstr,
      op: (util.U256, util.U256) => Boolean
  )(x: Val, y: Val): ExeResult[Val.Bool] =
    (x, y) match {
      case (a: Val.U256, b: Val.U256) => Right(Val.Bool(op(a.v, b.v)))
      case _                          => Left(BinaryArithmeticInstr.error(x, y, instr))
    }
}
object I64Add extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64SafeOp(this, _.add(_))(x, y)
}
object I64Sub extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64SafeOp(this, _.sub(_))(x, y)
}
object I64Mul extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64SafeOp(this, _.mul(_))(x, y)
}
object I64Div extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64SafeOp(this, _.div(_))(x, y)
}
object I64Mod extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64SafeOp(this, _.mod(_))(x, y)
}
object EqI64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64Comp(this, _.==(_))(x, y)
}
object NeI64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64Comp(this, _.!=(_))(x, y)
}
object LtI64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64Comp(this, _.<(_))(x, y)
}
object LeI64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64Comp(this, _.<=(_))(x, y)
}
object GtI64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64Comp(this, _.>(_))(x, y)
}
object GeI64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i64Comp(this, _.>=(_))(x, y)
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
object EqU64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64Comp(this, _.==(_))(x, y)
}
object NeU64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64Comp(this, _.!=(_))(x, y)
}
object LtU64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64Comp(this, _.<(_))(x, y)
}
object LeU64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64Comp(this, _.<=(_))(x, y)
}
object GtU64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64Comp(this, _.>(_))(x, y)
}
object GeU64 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u64Comp(this, _.>=(_))(x, y)
}
object I256Add extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.add(_))(x, y)
}
object I256Sub extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.sub(_))(x, y)
}
object I256Mul extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.mul(_))(x, y)
}
object I256Div extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.div(_))(x, y)
}
object I256Mod extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.mod(_))(x, y)
}
object EqI256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.==(_))(x, y)
}
object NeI256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.!=(_))(x, y)
}
object LtI256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.<(_))(x, y)
}
object LeI256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.<=(_))(x, y)
}
object GtI256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.>(_))(x, y)
}
object GeI256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.>=(_))(x, y)
}
object U256Add extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.add(_))(x, y)
}
object U256Sub extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.sub(_))(x, y)
}
object U256Mul extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.mul(_))(x, y)
}
object U256Div extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.div(_))(x, y)
}
object U256Mod extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.mod(_))(x, y)
}
object EqU256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.==(_))(x, y)
}
object NeU256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.!=(_))(x, y)
}
object LtU256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.<(_))(x, y)
}
object LeU256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.<=(_))(x, y)
}
object GtU256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.>(_))(x, y)
}
object GeU256 extends BinaryArithmeticInstr {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.>=(_))(x, y)
}

case object NotBool extends InstrCompanion0 {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bool <- frame.popT[Val.Bool]()
      _    <- frame.push(bool.not)
    } yield ()
  }
}
case object AndBool extends InstrCompanion0 {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bool2 <- frame.popT[Val.Bool]()
      bool1 <- frame.popT[Val.Bool]()
      _     <- frame.push(bool1.and(bool2))
    } yield ()
  }
}
case object OrBool extends InstrCompanion0 {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bool2 <- frame.popT[Val.Bool]()
      bool1 <- frame.popT[Val.Bool]()
      _     <- frame.push(bool1.or(bool2))
    } yield ()
  }
}

trait ConversionInstr[R <: Val, U <: Val] extends InstrCompanion0 {
  def converse(from: R): ExeResult[U]

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      from <- frame.popT[R]()
      to   <- converse(from)
      _    <- frame.push(to)
    } yield ()
  }
}

case object ByteToI64 extends ConversionInstr[Val.Byte, Val.I64] {
  override def converse(from: Val.Byte): ExeResult[Val.I64] = {
    Right(Val.I64(util.I64.from(from.v & 0xFFL)))
  }
}
case object ByteToU64 extends ConversionInstr[Val.Byte, Val.U64] {
  override def converse(from: Val.Byte): ExeResult[Val.U64] = {
    Right(Val.U64(util.U64.unsafe(from.v & 0xFFL)))
  }
}
case object ByteToI256 extends ConversionInstr[Val.Byte, Val.I256] {
  override def converse(from: Val.Byte): ExeResult[Val.I256] = {
    Right(Val.I256(util.I256.from(from.v & 0xFFL)))
  }
}
case object ByteToU256 extends ConversionInstr[Val.Byte, Val.U256] {
  override def converse(from: Val.Byte): ExeResult[Val.U256] = {
    Right(Val.U256(util.U256.unsafe(from.v & 0xFFL)))
  }
}

case object I64ToByte extends ConversionInstr[Val.I64, Val.Byte] {
  override def converse(from: Val.I64): ExeResult[Val.Byte] = {
    val underlying = from.v.v
    if (underlying >= 0 && underlying < 0x100) Right(Val.Byte(underlying.toByte)) // unsigned Byte
    else Left(InvalidConversion(from, Val.Byte))
  }
}
case object I64ToU64 extends ConversionInstr[Val.I64, Val.U64] {
  override def converse(from: Val.I64): ExeResult[Val.U64] = {
    util.U64.fromI64(from.v).map(Val.U64.apply).toRight(InvalidConversion(from, Val.U64))
  }
}
case object I64ToI256 extends ConversionInstr[Val.I64, Val.I256] {
  override def converse(from: Val.I64): ExeResult[Val.I256] = {
    Right(Val.I256(util.I256.fromI64(from.v)))
  }
}
case object I64ToU256 extends ConversionInstr[Val.I64, Val.U256] {
  override def converse(from: Val.I64): ExeResult[Val.U256] = {
    util.U256.fromI64(from.v).map(Val.U256.apply).toRight(InvalidConversion(from, Val.U256))
  }
}

case object U64ToByte extends ConversionInstr[Val.U64, Val.Byte] {
  override def converse(from: Val.U64): ExeResult[Val.Byte] = {
    val underlying = from.v.v
    if (underlying < 0x100) Right(Val.Byte(underlying.toByte)) // unsigned Byte
    else Left(InvalidConversion(from, Val.Byte))
  }
}
case object U64ToI64 extends ConversionInstr[Val.U64, Val.I64] {
  override def converse(from: Val.U64): ExeResult[Val.I64] = {
    util.I64.fromU64(from.v).map(Val.I64.apply).toRight(InvalidConversion(from, Val.I64))
  }
}
case object U64ToI256 extends ConversionInstr[Val.U64, Val.I256] {
  override def converse(from: Val.U64): ExeResult[Val.I256] = {
    Right(Val.I256(util.I256.fromU64(from.v)))
  }
}
case object U64ToU256 extends ConversionInstr[Val.U64, Val.U256] {
  override def converse(from: Val.U64): ExeResult[Val.U256] = {
    Right(Val.U256(util.U256.fromU64(from.v)))
  }
}

case object I256ToByte extends ConversionInstr[Val.I256, Val.Byte] {
  override def converse(from: Val.I256): ExeResult[Val.Byte] = {
    I256ToI64.converse(from).flatMap(I64ToByte.converse) // TODO: optimize this
  }
}
case object I256ToI64 extends ConversionInstr[Val.I256, Val.I64] {
  override def converse(from: Val.I256): ExeResult[Val.I64] = {
    util.I64.fromI256(from.v).map(Val.I64.apply).toRight(InvalidConversion(from, Val.I64))
  }
}
case object I256ToU64 extends ConversionInstr[Val.I256, Val.U64] {
  override def converse(from: Val.I256): ExeResult[Val.U64] = {
    util.U64.fromI256(from.v).map(Val.U64.apply).toRight(InvalidConversion(from, Val.U64))
  }
}
case object I256ToU256 extends ConversionInstr[Val.I256, Val.U256] {
  override def converse(from: Val.I256): ExeResult[Val.U256] = {
    util.U256.fromI256(from.v).map(Val.U256.apply).toRight(InvalidConversion(from, Val.U256))
  }
}

case object U256ToByte extends ConversionInstr[Val.U256, Val.Byte] {
  override def converse(from: Val.U256): ExeResult[Val.Byte] = {
    U256ToU64.converse(from).flatMap(U64ToByte.converse)
  }
}
case object U256ToI64 extends ConversionInstr[Val.U256, Val.I64] {
  override def converse(from: Val.U256): ExeResult[Val.I64] = {
    util.I64.fromU256(from.v).map(Val.I64.apply).toRight(InvalidConversion(from, Val.I64))
  }
}
case object U256ToU64 extends ConversionInstr[Val.U256, Val.U64] {
  override def converse(from: Val.U256): ExeResult[Val.U64] = {
    util.U64.fromU256(from.v).map(Val.U64.apply).toRight(InvalidConversion(from, Val.U64))
  }
}
case object U256ToI256 extends ConversionInstr[Val.U256, Val.I256] {
  override def converse(from: Val.U256): ExeResult[Val.I256] = {
    util.I256.fromU256(from.v).map(Val.I256.apply).toRight(InvalidConversion(from, Val.I256))
  }
}

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

case class Offset(offset: Byte) extends ControlInstr {
  override def serialize(): ByteString = ByteString(Offset.code, offset)

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.offsetPC(Bytes.toPosInt(offset))
  }
}
object Offset extends InstrCompanion1[Byte]

trait IfJumpInstr extends ControlInstr {
  def code: Byte
  def offset: Byte
  def condition(value: Val.Bool): Boolean

  override def serialize(): ByteString = ByteString(code, offset)

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value <- frame.popT[Val.Bool]()
      _     <- if (condition(value)) frame.offsetPC(Bytes.toPosInt(offset)) else Right(())
    } yield ()
  }
}
case class IfTrue(offset: Byte) extends IfJumpInstr {
  override def code: Byte = IfTrue.code

  override def condition(value: Val.Bool): Boolean = value.v
}
case object IfTrue extends InstrCompanion1[Byte]
case class IfFalse(offset: Byte) extends IfJumpInstr {
  override def code: Byte = IfFalse.code

  override def condition(value: Val.Bool): Boolean = !value.v
}
case object IfFalse extends InstrCompanion1[Byte]

trait BranchInstr[T] extends ControlInstr {
  def code: Byte
  def offset: Byte
  def condition(value1: T, value2: T): Boolean

  override def serialize(): ByteString = ByteString(code, offset)

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value2 <- frame.popT[T]()
      value1 <- frame.popT[T]()
      _      <- if (condition(value1, value2)) frame.offsetPC(Bytes.toPosInt(offset)) else Right(())
    } yield ()
  }
}
case class IfAnd(offset: Byte) extends BranchInstr[Val.Bool] {
  override def code: Byte = IfAnd.code

  override def condition(value1: Val.Bool, value2: Val.Bool): Boolean = value1.v && value2.v
}
case object IfAnd extends InstrCompanion1[Byte]
case class IfOr(offset: Byte) extends BranchInstr[Val.Bool] {
  override def code: Byte = IfOr.code

  override def condition(value1: Val.Bool, value2: Val.Bool): Boolean = value1.v || value2.v
}
case object IfOr extends InstrCompanion1[Byte]
case class IfNotAnd(offset: Byte) extends BranchInstr[Val.Bool] {
  override def code: Byte = IfNotAnd.code

  override def condition(value1: Val.Bool, value2: Val.Bool): Boolean = !(value1.v && value2.v)
}
case object IfNotAnd extends InstrCompanion1[Byte]
case class IfNotOr(offset: Byte) extends BranchInstr[Val.Bool] {
  override def code: Byte = IfNotOr.code

  override def condition(value1: Val.Bool, value2: Val.Bool): Boolean = !(value1.v || value2.v)
}
case object IfNotOr extends InstrCompanion1[Byte]
case class IfEqI64(offset: Byte) extends BranchInstr[Val.I64] {
  override def code: Byte = IfEqI64.code

  override def condition(value1: Val.I64, value2: Val.I64): Boolean = value1.v == value2.v
}
object IfEqI64 extends InstrCompanion1[Byte]
case class IfNeI64(offset: Byte) extends BranchInstr[Val.I64] {
  override def code: Byte = IfNeI64.code

  override def condition(value1: Val.I64, value2: Val.I64): Boolean = value1.v != value2.v
}
object IfNeI64 extends InstrCompanion1[Byte]
case class IfLtI64(offset: Byte) extends BranchInstr[Val.I64] {
  override def code: Byte = IfLtI64.code

  override def condition(value1: Val.I64, value2: Val.I64): Boolean = value1.v < value2.v
}
object IfLtI64 extends InstrCompanion1[Byte]
case class IfLeI64(offset: Byte) extends BranchInstr[Val.I64] {
  override def code: Byte = IfLeI64.code

  override def condition(value1: Val.I64, value2: Val.I64): Boolean = value1.v <= value2.v
}
object IfLeI64 extends InstrCompanion1[Byte]
case class IfGtI64(offset: Byte) extends BranchInstr[Val.I64] {
  override def code: Byte = IfGtI64.code

  override def condition(value1: Val.I64, value2: Val.I64): Boolean = value1.v > value2.v
}
object IfGtI64 extends InstrCompanion1[Byte]
case class IfGeI64(offset: Byte) extends BranchInstr[Val.I64] {
  override def code: Byte = IfGeI64.code

  override def condition(value1: Val.I64, value2: Val.I64): Boolean = value1.v >= value2.v
}
object IfGeI64 extends InstrCompanion1[Byte]
case class IfEqU64(offset: Byte) extends BranchInstr[Val.U64] {
  override def code: Byte = IfEqU64.code

  override def condition(value1: Val.U64, value2: Val.U64): Boolean = value1.v == value2.v
}
object IfEqU64 extends InstrCompanion1[Byte]
case class IfNeU64(offset: Byte) extends BranchInstr[Val.U64] {
  override def code: Byte = IfNeU64.code

  override def condition(value1: Val.U64, value2: Val.U64): Boolean = value1.v != value2.v
}
object IfNeU64 extends InstrCompanion1[Byte]
case class IfLtU64(offset: Byte) extends BranchInstr[Val.U64] {
  override def code: Byte = IfLtU64.code

  override def condition(value1: Val.U64, value2: Val.U64): Boolean = value1.v < value2.v
}
object IfLtU64 extends InstrCompanion1[Byte]
case class IfLeU64(offset: Byte) extends BranchInstr[Val.U64] {
  override def code: Byte = IfLeU64.code

  override def condition(value1: Val.U64, value2: Val.U64): Boolean = value1.v <= value2.v
}
object IfLeU64 extends InstrCompanion1[Byte]
case class IfGtU64(offset: Byte) extends BranchInstr[Val.U64] {
  override def code: Byte = IfGtU64.code

  override def condition(value1: Val.U64, value2: Val.U64): Boolean = value1.v > value2.v
}
object IfGtU64 extends InstrCompanion1[Byte]
case class IfGeU64(offset: Byte) extends BranchInstr[Val.U64] {
  override def code: Byte = IfGeU64.code

  override def condition(value1: Val.U64, value2: Val.U64): Boolean = value1.v >= value2.v
}
object IfGeU64 extends InstrCompanion1[Byte]
case class IfEqI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfEqI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v == value2.v
}
object IfEqI256 extends InstrCompanion1[Byte]
case class IfNeI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfNeI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v != value2.v
}
object IfNeI256 extends InstrCompanion1[Byte]
case class IfLtI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfLtI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v < value2.v
}
object IfLtI256 extends InstrCompanion1[Byte]
case class IfLeI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfLeI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v <= value2.v
}
object IfLeI256 extends InstrCompanion1[Byte]
case class IfGtI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfGtI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v > value2.v
}
object IfGtI256 extends InstrCompanion1[Byte]
case class IfGeI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfGeI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v >= value2.v
}
object IfGeI256 extends InstrCompanion1[Byte]
case class IfEqU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfEqU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v == value2.v
}
object IfEqU256 extends InstrCompanion1[Byte]
case class IfNeU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfNeU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v != value2.v
}
object IfNeU256 extends InstrCompanion1[Byte]
case class IfLtU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfLtU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v < value2.v
}
object IfLtU256 extends InstrCompanion1[Byte]
case class IfLeU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfLeU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v <= value2.v
}
object IfLeU256 extends InstrCompanion1[Byte]
case class IfGtU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfGtU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v > value2.v
}
object IfGtU256 extends InstrCompanion1[Byte]
case class IfGeU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfGeU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v >= value2.v
}
object IfGeU256 extends InstrCompanion1[Byte]

trait CallInstr extends StatelessInstr
case class CallLocal(index: Byte) extends CallInstr {
  override def serialize(): ByteString = ByteString(CallLocal.code, index)

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      newFrame <- frame.methodFrame(Bytes.toPosInt(index))
      _        <- newFrame.execute()
    } yield ()
  }
}
object CallLocal   extends InstrCompanion1[Byte]
trait CallExternal extends CallInstr

trait ReturnInstr extends StatelessInstr
case object Return extends ReturnInstr with InstrCompanion0 {

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    val returnType = frame.method.returnType
    for {
      value <- frame.opStack.pop(returnType.length)
      _     <- if (value.map(_.tpe) == returnType) Right(()) else Left(InvalidReturnType)
      _     <- frame.returnTo(value)
    } yield frame.complete()
  }
}

trait CryptoInstr   extends StatelessInstr
trait HashAlg       extends CryptoInstr
trait Signature     extends CryptoInstr
trait EllipticCurve extends CryptoInstr

trait CheckEqT[T] extends CryptoInstr with InstrCompanion0 {
  def check(x: T, y: T): ExeResult[Unit] = {
    if (x == y) Right(()) else Left(EqualityFailed)
  }

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      x <- frame.popT[T]()
      y <- frame.popT[T]()
      _ <- check(x, y)
    } yield ()
  }
}

case object CheckEqBool      extends CheckEqT[Val.Bool]
case object CheckEqByte      extends CheckEqT[Val.Byte]
case object CheckEqI64       extends CheckEqT[Val.I64]
case object CheckEqU64       extends CheckEqT[Val.U64]
case object CheckEqI256      extends CheckEqT[Val.I256]
case object CheckEqU256      extends CheckEqT[Val.U256]
case object CheckEqByte32    extends CheckEqT[Val.Byte32]
case object CheckEqBoolVec   extends CheckEqT[Val.BoolVec]
case object CheckEqByteVec   extends CheckEqT[Val.ByteVec]
case object CheckEqI64Vec    extends CheckEqT[Val.I64Vec]
case object CheckEqU64Vec    extends CheckEqT[Val.U64Vec]
case object CheckEqI256Vec   extends CheckEqT[Val.I256Vec]
case object CheckEqU256Vec   extends CheckEqT[Val.U256Vec]
case object CheckEqByte32Vec extends CheckEqT[Val.Byte32Vec]

abstract class Keccak256T[T] extends HashAlg with InstrCompanion0 {
  def convert(t: T): ByteString

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value <- frame.popT[T]()
      _ <- {
        val bs   = convert(value)
        val hash = Keccak256.hash(bs)
        frame.push(Val.Byte32.from(hash))
      }
    } yield ()
  }
}

case object Keccak256Byte32 extends Keccak256T[Val.Byte32] {
  override def convert(t: Val.Byte32): ByteString = t.v.bytes
}

case object Keccak256ByteVec extends Keccak256T[Val.ByteVec] {
  override def convert(t: Val.ByteVec): ByteString =
    ByteString.fromArrayUnsafe(Array.from(t.a.view.map(_.v)))
}

case object CheckSignature extends Signature with InstrCompanion0 {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    val rawData    = frame.ctx.txHash.bytes
    val signatures = frame.ctx.signatures
    for {
      rawPublicKey <- frame.popT[Val.Byte32]()
      signature    <- signatures.pop()
      _ <- {
        val publicKey = ED25519PublicKey.unsafe(rawPublicKey.v.bytes)
        if (ED25519.verify(rawData, signature, publicKey)) Right(()) else Left(VerificationFailed)
      }
    } yield ()
  }
}

trait ContextInstr    extends StatelessInstr
trait BlockInfo       extends ContextInstr
trait TransactionInfo extends ContextInstr
trait InputInfo       extends ContextInstr
trait OutputInfo      extends ContextInstr

trait ContractInfo extends StatefulInstr
