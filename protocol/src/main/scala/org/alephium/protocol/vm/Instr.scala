// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.vm

import scala.annotation.switch
import scala.collection.immutable.ArraySeq
import scala.collection.mutable

import akka.util.ByteString

import org.alephium.crypto._
import org.alephium.protocol.{Hash, PublicKey, SignatureSchema}
import org.alephium.serde.{deserialize => decode, _}
import org.alephium.util
import org.alephium.util.{AVector, Bytes, Collection}

// scalastyle:off file.size.limit number.of.types

sealed trait Instr[-Ctx <: Context] extends GasSchedule {
  def serialize(): ByteString

  def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit]
}

sealed trait InstrWithSimpleGas[-Ctx <: Context] extends GasSimple {
  def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _ <- frame.ctx.chargeGas(this)
      _ <- _runWith(frame)
    } yield ()
  }

  def _runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit]
}

object Instr {
  implicit val statelessSerde: Serde[Instr[StatelessContext]] = new Serde[Instr[StatelessContext]] {
    override def serialize(input: Instr[StatelessContext]): ByteString = input.serialize()

    @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
    override def _deserialize(input: ByteString): SerdeResult[Staging[Instr[StatelessContext]]] = {
      for {
        code <- input.headOption.toRight(SerdeError.incompleteData(1, 0))
        instrCompanion <- getStatelessCompanion(code).toRight(
          SerdeError.validation(s"Instruction - invalid code $code")
        )
        output <- instrCompanion.deserialize[StatelessContext](input.tail)
      } yield output
    }
  }
  implicit val statefulSerde: Serde[Instr[StatefulContext]] = new Serde[Instr[StatefulContext]] {
    override def serialize(input: Instr[StatefulContext]): ByteString = input.serialize()

    @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
    override def _deserialize(input: ByteString): SerdeResult[Staging[Instr[StatefulContext]]] = {
      for {
        code <- input.headOption.toRight(SerdeError.incompleteData(1, 0))
        instrCompanion <- getStatefulCompanion(code).toRight(
          SerdeError.validation(s"Instruction - invalid code $code")
        )
        output <- instrCompanion.deserialize[StatefulContext](input.tail)
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
    ConstTrue, ConstFalse,
    I256Const0, I256Const1, I256Const2, I256Const3, I256Const4, I256Const5, I256ConstN1,
    U256Const0, U256Const1, U256Const2, U256Const3, U256Const4, U256Const5,
    I256Const, U256Const,
    BytesConst, AddressConst,
    LoadLocal, StoreLocal,
    Pop, Pop2, Dup, Dup2, Swap,
    I256Add, I256Sub, I256Mul, I256Div, I256Mod, EqI256, NeI256, LtI256, LeI256, GtI256, GeI256,
    U256Add, U256Sub, U256Mul, U256Div, U256Mod, EqU256, NeU256, LtU256, LeU256, GtU256, GeU256,
    NotBool, AndBool, OrBool,
                ByteToI256, ByteToU256,
    I256ToByte,             I256ToU256,
    U256ToByte, U256ToI256,
    Forward, Backward,
    IfTrue, IfFalse, IfAnd, IfOr, IfNotAnd, IfNotOr, // TODO: support long branches, 256 instrs rn
    IfEqI256, IfNeI256, IfLtI256, IfLeI256, IfGtI256, IfGeI256,
    IfEqU256, IfNeU256, IfLtU256, IfLeU256, IfGtU256, IfGeU256,
    CallLocal, Return,
    CheckEqBool, CheckEqByte, CheckEqI256, CheckEqU256, CheckEqByteVec,
    CheckEqBoolVec, CheckEqByteVec, CheckEqI256Vec, CheckEqU256Vec,
    Blake2bByteVec, Keccak256ByteVec, CheckSignature
  )
  val statefulInstrs: ArraySeq[InstrCompanion[StatefulContext]]   = statelessInstrs ++
    ArraySeq[InstrCompanion[StatefulContext]](
      LoadField, StoreField, CallExternal,
      ApproveAlf, ApproveToken, AlfRemaining, TokenRemaining,
      TransferAlf, TransferAlfFromSelf, TransferAlfToSelf, TransferToken, TransferTokenFromSelf, TransferTokenToSelf,
      CreateContract, SelfAddress, SelfTokenId, IssueToken
    )
  // format: on

  val toCode: Map[InstrCompanion[_], Int] = statefulInstrs.zipWithIndex.toMap
}

sealed trait StatefulInstr  extends Instr[StatefulContext] with GasSchedule                     {}
sealed trait StatelessInstr extends StatefulInstr with Instr[StatelessContext] with GasSchedule {}
sealed trait StatefulInstrSimpleGas
    extends StatefulInstr
    with InstrWithSimpleGas[StatefulContext]
    with GasSimple {}
sealed trait StatelessInstrSimpleGas
    extends StatelessInstr
    with InstrWithSimpleGas[StatelessContext]
    with GasSimple {}

sealed trait InstrCompanion[-Ctx <: Context] {
  def deserialize[C <: Ctx](input: ByteString): SerdeResult[Staging[Instr[C]]]
}

sealed abstract class InstrCompanion1[Ctx <: Context, T: Serde] extends InstrCompanion[Ctx] {
  lazy val code: Byte = Instr.toCode(this).toByte

  def apply(t: T): Instr[Ctx]

  @inline def from[C <: Ctx](t: T): Instr[C] = apply(t)

  override def deserialize[C <: Ctx](input: ByteString): SerdeResult[Staging[Instr[C]]] = {
    serdeImpl[T]._deserialize(input).map(_.mapValue(from))
  }
}

sealed abstract class StatelessInstrCompanion1[T: Serde]
    extends InstrCompanion1[StatelessContext, T]

sealed abstract class StatefulInstrCompanion1[T: Serde] extends InstrCompanion1[StatefulContext, T]

sealed trait StatelessInstrCompanion0
    extends InstrCompanion[StatelessContext]
    with Instr[StatelessContext]
    with GasSchedule {
  lazy val code: Byte = Instr.toCode(this).toByte

  def serialize(): ByteString = ByteString(code)

  def deserialize[C <: StatelessContext](input: ByteString): SerdeResult[Staging[Instr[C]]] =
    Right(Staging(this, input))
}

sealed trait StatefulInstrCompanion0
    extends InstrCompanion[StatefulContext]
    with Instr[StatefulContext]
    with GasSchedule {
  lazy val code: Byte = Instr.toCode(this).toByte

  def serialize(): ByteString = ByteString(code)

  def deserialize[C <: StatefulContext](input: ByteString): SerdeResult[Staging[Instr[C]]] =
    Right(Staging(this, input))
}

sealed trait OperandStackInstr extends StatelessInstrSimpleGas with GasSimple {}

sealed trait ConstInstr extends OperandStackInstr with GasVeryLow
object ConstInstr {
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

sealed trait ConstInstr0 extends ConstInstr with StatelessInstrCompanion0 {
  def const: Val

  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.push(const)
  }
}

sealed abstract class ConstInstr1[T <: Val] extends ConstInstr {
  def const: T

  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.push(const)
  }
}

object ConstTrue  extends ConstInstr0 { val const: Val = Val.Bool(true)  }
object ConstFalse extends ConstInstr0 { val const: Val = Val.Bool(false) }

object I256ConstN1 extends ConstInstr0 { val const: Val = Val.I256(util.I256.NegOne)   }
object I256Const0  extends ConstInstr0 { val const: Val = Val.I256(util.I256.Zero)     }
object I256Const1  extends ConstInstr0 { val const: Val = Val.I256(util.I256.One)      }
object I256Const2  extends ConstInstr0 { val const: Val = Val.I256(util.I256.Two)      }
object I256Const3  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(3L)) }
object I256Const4  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(4L)) }
object I256Const5  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(5L)) }

object U256Const0 extends ConstInstr0 { val const: Val = Val.U256(util.U256.Zero)       }
object U256Const1 extends ConstInstr0 { val const: Val = Val.U256(util.U256.One)        }
object U256Const2 extends ConstInstr0 { val const: Val = Val.U256(util.U256.Two)        }
object U256Const3 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(3L)) }
object U256Const4 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(4L)) }
object U256Const5 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(5L)) }

final case class I256Const(const: Val.I256) extends ConstInstr1[Val.I256] {
  override def serialize(): ByteString =
    ByteString(I256Const.code) ++ serdeImpl[util.I256].serialize(const.v)
}
object I256Const extends StatelessInstrCompanion1[Val.I256]
final case class U256Const(const: Val.U256) extends ConstInstr1[Val.U256] {
  override def serialize(): ByteString =
    ByteString(U256Const.code) ++ serdeImpl[util.U256].serialize(const.v)
}
object U256Const extends StatelessInstrCompanion1[Val.U256]

final case class BytesConst(const: Val.ByteVec) extends ConstInstr1[Val.ByteVec] {
  override def serialize(): ByteString =
    ByteString(BytesConst.code) ++ serdeImpl[Val.ByteVec].serialize(const)
}
object BytesConst extends StatelessInstrCompanion1[Val.ByteVec]

final case class AddressConst(const: Val.Address) extends ConstInstr1[Val.Address] {
  override def serialize(): ByteString =
    ByteString(AddressConst.code) ++ serdeImpl[Val.Address].serialize(const)
}
object AddressConst extends StatelessInstrCompanion1[Val.Address]

// Note: 0 <= index <= 0xFF
final case class LoadLocal(index: Byte) extends OperandStackInstr with GasVeryLow {
  override def serialize(): ByteString = ByteString(LoadLocal.code, index)
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getLocal(Bytes.toPosInt(index))
      _ <- frame.push(v)
    } yield ()
  }
}
object LoadLocal extends StatelessInstrCompanion1[Byte]
final case class StoreLocal(index: Byte) extends OperandStackInstr with GasVeryLow {
  override def serialize(): ByteString = ByteString(StoreLocal.code, index)
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.pop()
      _ <- frame.setLocal(Bytes.toPosInt(index), v)
    } yield ()
  }
}
object StoreLocal extends StatelessInstrCompanion1[Byte]

sealed trait FieldInstr extends StatefulInstrSimpleGas with GasSimple {}
final case class LoadField(index: Byte) extends FieldInstr with GasVeryLow {
  override def serialize(): ByteString = ByteString(LoadField.code, index)
  override def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getField(Bytes.toPosInt(index))
      _ <- frame.push(v)
    } yield ()
  }
}
object LoadField extends StatefulInstrCompanion1[Byte]
final case class StoreField(index: Byte) extends FieldInstr with GasVeryLow {
  override def serialize(): ByteString = ByteString(StoreField.code, index)
  override def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.pop()
      _ <- frame.setField(Bytes.toPosInt(index), v)
    } yield ()
  }
}
object StoreField extends StatefulInstrCompanion1[Byte]

sealed trait PureStackInstr extends OperandStackInstr with StatelessInstrCompanion0 with GasVeryLow

case object Pop extends PureStackInstr {
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.remove(1)
  }
}
case object Pop2 extends PureStackInstr {
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.remove(2)
  }
}
object Dup extends PureStackInstr {
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.dup(1)
  }
}
object Dup2 extends PureStackInstr {
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.dup(2)
  }
}
object Swap extends PureStackInstr {
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.swap(2)
  }
}

sealed trait ArithmeticInstr
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasSimple {}

sealed trait BinaryArithmeticInstr extends ArithmeticInstr with GasSimple {
  protected def op(x: Val, y: Val): ExeResult[Val]

  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
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

  @inline def i256SafeOp(
      instr: ArithmeticInstr,
      op: (util.I256, util.I256) => Option[util.I256]
  )(x: Val, y: Val): ExeResult[Val.I256] =
    (x, y) match {
      case (a: Val.I256, b: Val.I256) =>
        op(a.v, b.v).map(Val.I256.apply).toRight(Right(BinaryArithmeticInstr.error(a, b, instr)))
      case _ => failed(BinaryArithmeticInstr.error(x, y, instr))
    }

  @inline def u256SafeOp(
      instr: ArithmeticInstr,
      op: (util.U256, util.U256) => Option[util.U256]
  )(x: Val, y: Val): ExeResult[Val.U256] =
    (x, y) match {
      case (a: Val.U256, b: Val.U256) =>
        op(a.v, b.v).map(Val.U256.apply).toRight(Right(BinaryArithmeticInstr.error(a, b, instr)))
      case _ => failed(BinaryArithmeticInstr.error(x, y, instr))
    }

  @inline def i256Comp(
      instr: ArithmeticInstr,
      op: (util.I256, util.I256) => Boolean
  )(x: Val, y: Val): ExeResult[Val.Bool] =
    (x, y) match {
      case (a: Val.I256, b: Val.I256) => Right(Val.Bool(op(a.v, b.v)))
      case _                          => failed(BinaryArithmeticInstr.error(x, y, instr))
    }

  @inline def u256Comp(
      instr: ArithmeticInstr,
      op: (util.U256, util.U256) => Boolean
  )(x: Val, y: Val): ExeResult[Val.Bool] =
    (x, y) match {
      case (a: Val.U256, b: Val.U256) => Right(Val.Bool(op(a.v, b.v)))
      case _                          => failed(BinaryArithmeticInstr.error(x, y, instr))
    }
}
object I256Add extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.add(_))(x, y)
}
object I256Sub extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.sub(_))(x, y)
}
object I256Mul extends BinaryArithmeticInstr with GasLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.mul(_))(x, y)
}
object I256Div extends BinaryArithmeticInstr with GasLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.div(_))(x, y)
}
object I256Mod extends BinaryArithmeticInstr with GasLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.mod(_))(x, y)
}
object EqI256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.==(_))(x, y)
}
object NeI256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.!=(_))(x, y)
}
object LtI256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.<(_))(x, y)
}
object LeI256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.<=(_))(x, y)
}
object GtI256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.>(_))(x, y)
}
object GeI256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(this, _.>=(_))(x, y)
}
object U256Add extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.add(_))(x, y)
}
object U256Sub extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.sub(_))(x, y)
}
object U256Mul extends BinaryArithmeticInstr with GasLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.mul(_))(x, y)
}
object U256Div extends BinaryArithmeticInstr with GasLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.div(_))(x, y)
}
object U256Mod extends BinaryArithmeticInstr with GasLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.mod(_))(x, y)
}
object EqU256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.==(_))(x, y)
}
object NeU256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.!=(_))(x, y)
}
object LtU256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.<(_))(x, y)
}
object LeU256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.<=(_))(x, y)
}
object GtU256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.>(_))(x, y)
}
object GeU256 extends BinaryArithmeticInstr with GasVeryLow {
  protected def op(x: Val, y: Val): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(this, _.>=(_))(x, y)
}

sealed trait LogicInstr
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasSimple {}
case object NotBool extends LogicInstr with GasVeryLow {
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bool <- frame.popT[Val.Bool]()
      _    <- frame.push(bool.not)
    } yield ()
  }
}
case object AndBool extends LogicInstr with GasVeryLow {
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bool2 <- frame.popT[Val.Bool]()
      bool1 <- frame.popT[Val.Bool]()
      _     <- frame.push(bool1.and(bool2))
    } yield ()
  }
}
case object OrBool extends LogicInstr with GasVeryLow {
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bool2 <- frame.popT[Val.Bool]()
      bool1 <- frame.popT[Val.Bool]()
      _     <- frame.push(bool1.or(bool2))
    } yield ()
  }
}

sealed trait ConversionInstr[R <: Val, U <: Val]
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasSimple {

  def converse(from: R): ExeResult[U]

  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      from <- frame.popT[R]()
      to   <- converse(from)
      _    <- frame.push(to)
    } yield ()
  }
}

case object ByteToI256 extends ConversionInstr[Val.Byte, Val.I256] with GasVeryLow {
  override def converse(from: Val.Byte): ExeResult[Val.I256] = {
    Right(Val.I256(util.I256.from(from.v & 0xffL)))
  }
}
case object ByteToU256 extends ConversionInstr[Val.Byte, Val.U256] with GasVeryLow {
  override def converse(from: Val.Byte): ExeResult[Val.U256] = {
    Right(Val.U256(util.U256.unsafe(from.v & 0xffL)))
  }
}

case object I256ToByte extends ConversionInstr[Val.I256, Val.Byte] with GasVeryLow {
  override def converse(from: Val.I256): ExeResult[Val.Byte] = {
    from.v.toByte.map(Val.Byte.apply).toRight(Right(InvalidConversion(from, Val.Byte)))
  }
}
case object I256ToU256 extends ConversionInstr[Val.I256, Val.U256] with GasVeryLow {
  override def converse(from: Val.I256): ExeResult[Val.U256] = {
    util.U256.fromI256(from.v).map(Val.U256.apply).toRight(Right(InvalidConversion(from, Val.U256)))
  }
}

case object U256ToByte extends ConversionInstr[Val.U256, Val.Byte] with GasVeryLow {
  override def converse(from: Val.U256): ExeResult[Val.Byte] = {
    from.v.toByte.map(Val.Byte.apply).toRight(Right(InvalidConversion(from, Val.U256)))
  }
}
case object U256ToI256 extends ConversionInstr[Val.U256, Val.I256] with GasVeryLow {
  override def converse(from: Val.U256): ExeResult[Val.I256] = {
    util.I256.fromU256(from.v).map(Val.I256.apply).toRight(Right(InvalidConversion(from, Val.I256)))
  }
}

sealed trait ObjectInstr   extends StatelessInstr with GasSchedule {}
sealed trait NewBooleanVec extends ObjectInstr with GasSchedule    {}
sealed trait NewByteVec    extends ObjectInstr with GasSchedule    {}
sealed trait NewI256Vec    extends ObjectInstr with GasSchedule    {}
sealed trait NewU256Vec    extends ObjectInstr with GasSchedule    {}
sealed trait NewByte256Vec extends ObjectInstr with GasSchedule    {}

sealed trait ControlInstr extends StatelessInstrSimpleGas with GasHigh

final case class Forward(offset: Byte) extends ControlInstr {
  override def serialize(): ByteString = ByteString(Forward.code, offset)

  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.offsetPC(Bytes.toPosInt(offset))
  }
}
object Forward extends StatelessInstrCompanion1[Byte]
final case class Backward(offset: Byte) extends ControlInstr {
  override def serialize(): ByteString = ByteString(Backward.code, offset)

  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.offsetPC(-Bytes.toPosInt(offset))
  }
}
object Backward extends StatelessInstrCompanion1[Byte]

sealed trait IfJumpInstr extends ControlInstr {
  def code: Byte
  def offset: Byte
  def condition(value: Val.Bool): Boolean

  override def serialize(): ByteString = ByteString(code, offset)

  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value <- frame.popT[Val.Bool]()
      _     <- if (condition(value)) frame.offsetPC(Bytes.toPosInt(offset)) else okay
    } yield ()
  }
}
final case class IfTrue(offset: Byte) extends IfJumpInstr {
  override def code: Byte = IfTrue.code

  override def condition(value: Val.Bool): Boolean = value.v
}
case object IfTrue extends StatelessInstrCompanion1[Byte]
final case class IfFalse(offset: Byte) extends IfJumpInstr {
  override def code: Byte = IfFalse.code

  override def condition(value: Val.Bool): Boolean = !value.v
}
case object IfFalse extends StatelessInstrCompanion1[Byte]

sealed trait BranchInstr[T <: Val] extends ControlInstr {
  def code: Byte
  def offset: Byte
  def condition(value1: T, value2: T): Boolean

  override def serialize(): ByteString = ByteString(code, offset)

  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value2 <- frame.popT[T]()
      value1 <- frame.popT[T]()
      _      <- if (condition(value1, value2)) frame.offsetPC(Bytes.toPosInt(offset)) else okay
    } yield ()
  }
}
final case class IfAnd(offset: Byte) extends BranchInstr[Val.Bool] {
  override def code: Byte = IfAnd.code

  override def condition(value1: Val.Bool, value2: Val.Bool): Boolean = value1.v && value2.v
}
case object IfAnd extends StatelessInstrCompanion1[Byte]
final case class IfOr(offset: Byte) extends BranchInstr[Val.Bool] {
  override def code: Byte = IfOr.code

  override def condition(value1: Val.Bool, value2: Val.Bool): Boolean = value1.v || value2.v
}
case object IfOr extends StatelessInstrCompanion1[Byte]
final case class IfNotAnd(offset: Byte) extends BranchInstr[Val.Bool] {
  override def code: Byte = IfNotAnd.code

  override def condition(value1: Val.Bool, value2: Val.Bool): Boolean = !(value1.v && value2.v)
}
case object IfNotAnd extends StatelessInstrCompanion1[Byte]
final case class IfNotOr(offset: Byte) extends BranchInstr[Val.Bool] {
  override def code: Byte = IfNotOr.code

  override def condition(value1: Val.Bool, value2: Val.Bool): Boolean = !(value1.v || value2.v)
}
case object IfNotOr extends StatelessInstrCompanion1[Byte]
final case class IfEqI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfEqI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v == value2.v
}
object IfEqI256 extends StatelessInstrCompanion1[Byte]
final case class IfNeI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfNeI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v != value2.v
}
object IfNeI256 extends StatelessInstrCompanion1[Byte]
final case class IfLtI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfLtI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v < value2.v
}
object IfLtI256 extends StatelessInstrCompanion1[Byte]
final case class IfLeI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfLeI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v <= value2.v
}
object IfLeI256 extends StatelessInstrCompanion1[Byte]
final case class IfGtI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfGtI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v > value2.v
}
object IfGtI256 extends StatelessInstrCompanion1[Byte]
final case class IfGeI256(offset: Byte) extends BranchInstr[Val.I256] {
  override def code: Byte = IfGeI256.code

  override def condition(value1: Val.I256, value2: Val.I256): Boolean = value1.v >= value2.v
}
object IfGeI256 extends StatelessInstrCompanion1[Byte]
final case class IfEqU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfEqU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v == value2.v
}
object IfEqU256 extends StatelessInstrCompanion1[Byte]
final case class IfNeU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfNeU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v != value2.v
}
object IfNeU256 extends StatelessInstrCompanion1[Byte]
final case class IfLtU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfLtU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v < value2.v
}
object IfLtU256 extends StatelessInstrCompanion1[Byte]
final case class IfLeU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfLeU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v <= value2.v
}
object IfLeU256 extends StatelessInstrCompanion1[Byte]
final case class IfGtU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfGtU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v > value2.v
}
object IfGtU256 extends StatelessInstrCompanion1[Byte]
final case class IfGeU256(offset: Byte) extends BranchInstr[Val.U256] {
  override def code: Byte = IfGeU256.code

  override def condition(value1: Val.U256, value2: Val.U256): Boolean = value1.v >= value2.v
}
object IfGeU256 extends StatelessInstrCompanion1[Byte]

sealed trait CallInstr
final case class CallLocal(index: Byte) extends CallInstr with StatelessInstr with GasCall {
  override def serialize(): ByteString = ByteString(CallLocal.code, index)

  // Implemented in frame instead
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = ???
}
object CallLocal extends StatelessInstrCompanion1[Byte]
final case class CallExternal(index: Byte) extends CallInstr with StatefulInstr with GasCall {
  override def serialize(): ByteString = ByteString(CallExternal.code, index)

  // Implemented in frame instead
  override def runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = ???
}
object CallExternal extends StatefulInstrCompanion1[Byte]

case object Return extends StatelessInstrSimpleGas with StatelessInstrCompanion0 with GasZero {
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    val returnType = frame.method.returnType
    for {
      value <- frame.opStack.pop(returnType.length)
      _     <- if (value.map(_.tpe) == returnType) okay else failed(InvalidReturnType)
      _     <- frame.returnTo(value)
    } yield frame.complete()
  }
}

sealed trait CryptoInstr extends StatelessInstr with GasSchedule                         {}
sealed trait Signature   extends CryptoInstr with StatelessInstrSimpleGas with GasSimple {}

sealed trait CheckEqT[T <: Val]
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasVeryLow {
  def check(x: T, y: T): ExeResult[Unit] = {
    if (x == y) okay else failed(EqualityFailed)
  }

  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      x <- frame.popT[T]()
      y <- frame.popT[T]()
      _ <- check(x, y)
    } yield ()
  }
}

case object CheckEqBool    extends CheckEqT[Val.Bool]
case object CheckEqByte    extends CheckEqT[Val.Byte]
case object CheckEqI256    extends CheckEqT[Val.I256]
case object CheckEqU256    extends CheckEqT[Val.U256]
case object CheckEqBoolVec extends CheckEqT[Val.BoolVec]
case object CheckEqByteVec extends CheckEqT[Val.ByteVec]
case object CheckEqI256Vec extends CheckEqT[Val.I256Vec]
case object CheckEqU256Vec extends CheckEqT[Val.U256Vec]
case object CheckEqAddress extends CheckEqT[Val.Address]

sealed abstract class HashAlg[T <: Val, H <: RandomBytes]
    extends CryptoInstr
    with StatelessInstrCompanion0
    with GasHash {
  def convert(t: T): ByteString

  def hash(bs: ByteString): H

  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      input <- frame.popT[T]()
      bytes = convert(input)
      _ <- frame.ctx.chargeGasWithSize(this, bytes.length)
      _ <- frame.push(Val.ByteVec.from(hash(bytes)))
    } yield ()
  }
}

object HashAlg {
  trait ByteVecConvertor {
    def convert(t: Val.ByteVec): ByteString =
      ByteString.fromArrayUnsafe(t.a.toArray)
  }

  trait Blake2bHash {
    def hash(bs: ByteString): Blake2b = Blake2b.hash(bs)
  }

  trait Keccak256Hash {
    def hash(bs: ByteString): Keccak256 = Keccak256.hash(bs)
  }
}

case object Blake2bByteVec
    extends HashAlg[Val.ByteVec, Blake2b]
    with HashAlg.ByteVecConvertor
    with HashAlg.Blake2bHash

// TODO: maybe remove Keccak from the VM
case object Keccak256ByteVec
    extends HashAlg[Val.ByteVec, Keccak256]
    with HashAlg.ByteVecConvertor
    with HashAlg.Keccak256Hash

case object CheckSignature extends Signature with StatelessInstrCompanion0 with GasSignature {
  override def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    val rawData    = frame.ctx.txId.bytes
    val signatures = frame.ctx.signatures
    for {
      rawPublicKey <- frame.popT[Val.ByteVec]()
      publicKey    <- PublicKey.from(rawPublicKey.a).toRight(Right(InvalidPublicKey))
      signature    <- signatures.pop()
      _ <- {
        if (SignatureSchema.verify(rawData, signature, publicKey)) {
          okay
        } else {
          failed(VerificationFailed)
        }
      }
    } yield ()
  }
}

sealed trait AssetInstr extends StatefulInstrSimpleGas with GasBalance

object ApproveAlf extends AssetInstr with StatefulInstrCompanion0 {
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.JavaSerializable",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable"
    )
  )
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      amount       <- frame.popT[Val.U256]()
      address      <- frame.popT[Val.Address]()
      balanceState <- frame.balanceStateOpt.toRight(Right(NonPayableFrame))
      _ <- balanceState
        .approveALF(address.lockupScript, amount.v)
        .toRight(Right(NotEnoughBalance))
    } yield ()
  }
}

object ApproveToken extends AssetInstr with StatefulInstrCompanion0 {
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.JavaSerializable",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable"
    )
  )
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      amount       <- frame.popT[Val.U256]()
      tokenIdRaw   <- frame.popT[Val.ByteVec]()
      tokenId      <- Hash.from(tokenIdRaw.a).toRight(Right(InvalidTokenId))
      address      <- frame.popT[Val.Address]()
      balanceState <- frame.balanceStateOpt.toRight(Right(NonPayableFrame))
      _ <- balanceState
        .approveToken(address.lockupScript, tokenId, amount.v)
        .toRight(Right(NotEnoughBalance))
    } yield ()
  }
}

object AlfRemaining extends AssetInstr with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address      <- frame.popT[Val.Address]()
      balanceState <- frame.balanceStateOpt.toRight(Right(NonPayableFrame))
      amount <- balanceState
        .alfRemaining(address.lockupScript)
        .toRight(Right(NoAlfBalanceForTheAddress))
      _ <- frame.push(Val.U256(amount))
    } yield ()
  }
}

object TokenRemaining extends AssetInstr with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      tokenIdRaw   <- frame.popT[Val.ByteVec]()
      address      <- frame.popT[Val.Address]()
      tokenId      <- Hash.from(tokenIdRaw.a).toRight(Right(InvalidTokenId))
      balanceState <- frame.balanceStateOpt.toRight(Right(NonPayableFrame))
      amount <- balanceState
        .tokenRemaining(address.lockupScript, tokenId)
        .toRight(Right(NoTokenBalanceForTheAddress))
      _ <- frame.push(Val.U256(amount))
    } yield ()
  }
}

sealed trait Transfer extends AssetInstr {
  def getContractLockupScript[C <: StatefulContext](frame: Frame[C]): ExeResult[LockupScript] = {
    frame.obj.addressOpt.toRight(Right(ExpectAContract)).map(LockupScript.p2c)
  }

  def transferAlf[C <: StatefulContext](
      frame: Frame[C],
      fromThunk: => ExeResult[LockupScript],
      toThunk: => ExeResult[LockupScript]
  ): ExeResult[Unit] = {
    for {
      amount       <- frame.popT[Val.U256]()
      to           <- toThunk
      from         <- fromThunk
      balanceState <- frame.balanceStateOpt.toRight(Right(NonPayableFrame))
      _            <- balanceState.useAlf(from, amount.v).toRight(Right(NotEnoughBalance))
      _ <- frame.ctx.outputBalances
        .addAlf(to, amount.v)
        .toRight(Right(BalanceOverflow))
    } yield ()
  }

  def transferToken[C <: StatefulContext](
      frame: Frame[C],
      fromThunk: => ExeResult[LockupScript],
      toThunk: => ExeResult[LockupScript]
  ): ExeResult[Unit] = {
    for {
      amount       <- frame.popT[Val.U256]()
      tokenIdRaw   <- frame.popT[Val.ByteVec]()
      tokenId      <- Hash.from(tokenIdRaw.a).toRight(Right(InvalidTokenId))
      to           <- toThunk
      from         <- fromThunk
      balanceState <- frame.balanceStateOpt.toRight(Right(NonPayableFrame))
      _ <- balanceState
        .useToken(from, tokenId, amount.v)
        .toRight(Right(NotEnoughBalance))
      _ <- frame.ctx.outputBalances
        .addToken(to, tokenId, amount.v)
        .toRight(Right(BalanceOverflow))
    } yield ()
  }
}

object TransferAlf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferAlf(
      frame,
      frame.popT[Val.Address]().map(_.lockupScript),
      frame.popT[Val.Address]().map(_.lockupScript)
    )
  }
}

object TransferAlfFromSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferAlf(
      frame,
      getContractLockupScript(frame),
      frame.popT[Val.Address]().map(_.lockupScript)
    )
  }
}

object TransferAlfToSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferAlf(
      frame,
      frame.popT[Val.Address]().map(_.lockupScript),
      getContractLockupScript(frame)
    )
  }
}

object TransferToken extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferToken(
      frame,
      frame.popT[Val.Address]().map(_.lockupScript),
      frame.popT[Val.Address]().map(_.lockupScript)
    )
  }
}

object TransferTokenFromSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferToken(
      frame,
      getContractLockupScript(frame),
      frame.popT[Val.Address]().map(_.lockupScript)
    )
  }
}

object TransferTokenToSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferToken(
      frame,
      frame.popT[Val.Address]().map(_.lockupScript),
      getContractLockupScript(frame)
    )
  }
}

sealed trait ContractInstr
    extends StatefulInstrSimpleGas
    with StatefulInstrCompanion0
    with GasSimple {}

object CreateContract extends ContractInstr with GasCreate {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      fieldsRaw <- frame.popT[Val.ByteVec]()
      fields <- decode[AVector[Val]](ByteString(fieldsRaw.a)).left.map(e =>
        Right(SerdeErrorCreateContract(e))
      )
      contractCodeRaw <- frame.popT[Val.ByteVec]()
      contractCode <- decode[StatefulContract](ByteString(contractCodeRaw.a)).left.map(e =>
        Right(SerdeErrorCreateContract(e))
      )
      balanceState <- frame.balanceStateOpt.toRight(Right(NonPayableFrame))
      balances     <- balanceState.approved.useForNewContract().toRight(Right(InvalidBalances))
      _            <- frame.ctx.createContract(contractCode, balances, fields)
    } yield ()
  }
}

object SelfAddress extends ContractInstr with GasVeryLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      addressHash <- frame.obj.addressOpt.toRight(Right(ExpectAContract))
      _           <- frame.push(Val.Address(LockupScript.p2c(addressHash)))
    } yield ()
  }
}

object SelfTokenId extends ContractInstr with GasVeryLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      addressHash <- frame.obj.addressOpt.toRight(Right(ExpectAContract))
      tokenId = addressHash // tokenId is addressHash
      _ <- frame.push(Val.ByteVec(mutable.ArraySeq.from(tokenId.bytes)))
    } yield ()
  }
}

object IssueToken extends ContractInstr with GasBalance {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _           <- Either.cond(frame.method.isPayable, (), Right(NonPayableFrame))
      addressHash <- frame.obj.addressOpt.toRight(Right(ExpectAContract))
      amount      <- frame.popT[Val.U256]()
      tokenId = addressHash // tokenId is addressHash
      _ <- frame.ctx.outputBalances
        .addToken(LockupScript.p2c(addressHash), tokenId, amount.v)
        .toRight(Right(BalanceOverflow))
    } yield ()
  }
}

//trait ContextInstr    extends StatelessInstr
//trait BlockInfo       extends ContextInstr
//trait TransactionInfo extends ContextInstr
//trait InputInfo       extends ContextInstr
//trait OutputInfo      extends ContextInstr

//trait ContractInfo extends StatefulInstr
