package org.alephium.protocol.vm

import org.alephium.util

sealed trait Instr[-Ctx <: Context] {
  def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit]
}
trait StatelessInstr extends Instr[StatelessContext]
trait StatefulInstr  extends Instr[StatefulContext]

trait OperandStackInstr extends StatelessInstr

trait ConstInstr extends OperandStackInstr {
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

case class LoadLocal(index: Int) extends OperandStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getLocal(index)
      _ <- frame.push(v)
    } yield ()
  }
}
case class StoreLocal(index: Int) extends OperandStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.pop()
      _ <- frame.setLocal(index, v)
    } yield ()
  }
}
case class LoadField(index: Int) extends OperandStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getField(index)
      _ <- frame.push(v)
    } yield ()
  }
}
case class StoreField(index: Int) extends OperandStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.pop()
      _ <- frame.setField(index, v)
    } yield ()
  }
}

case object Pop extends OperandStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.remove(1)
  }
}
case object Pop2 extends OperandStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.remove(2)
  }
}
object Dup extends OperandStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.dup(1)
  }
}
object Dup2 extends OperandStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.dup(2)
  }
}
object Swap extends OperandStackInstr {
  override def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.swap(2)
  }
}

trait ArithmeticInstr extends StatelessInstr

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
