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

package org.alephium.ralph

import org.alephium.protocol.vm._
import org.alephium.util.{I256, U256}

sealed trait Operator {
  def calc(values: Seq[Val]): Either[String, Val]
  def getReturnType(argsType: Seq[Type]): Seq[Type]
  def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]]
}

sealed trait ArithOperator extends Operator {
  def getReturnType(argsType: Seq[Type]): Seq[Type] = {
    if (argsType.length != 2 || argsType(0) != argsType(1) || !argsType(0).toVal.isNumeric) {
      throw Compiler.Error(s"Invalid param types $argsType for ArithOperator", None)
    } else {
      Seq(argsType(0))
    }
  }
}
object ArithOperator {
  private def binary(
      operator: String,
      i256Instr: BinaryArithmeticInstr[Val.I256],
      u256Instr: BinaryArithmeticInstr[Val.U256],
      i256Func: (I256, I256) => Option[I256],
      u256Func: (U256, U256) => Option[U256]
  ): ArithOperator = {
    new ArithOperator {
      def calc(values: Seq[Val]): Either[String, Val] = {
        values match {
          case Seq(left: Val.I256, right: Val.I256) =>
            i256Func(left.v, right.v).map(Val.I256(_)).toRight("I256 overflow")
          case Seq(left: Val.U256, right: Val.U256) =>
            u256Func(left.v, right.v).map(Val.U256(_)).toRight("U256 overflow")
          case _ => Left(s"Expect two I256 or two U256 values for $operator operator")
        }
      }

      override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
        argsType(0) match {
          case Type.I256 => Seq(i256Instr)
          case Type.U256 => Seq(u256Instr)
          case _         => throw Compiler.Error(s"Expect I256/U256 for $operator operator", None)
        }
      }
    }
  }

  private def u256Binary(
      name: String,
      instr: BinaryInstr[Val.U256],
      func: (U256, U256) => U256
  ): ArithOperator = {
    new ArithOperator {
      def calc(values: Seq[Val]): Either[String, Val] = {
        values match {
          case Seq(left: Val.U256, right: Val.U256) => Right(Val.U256(func(left.v, right.v)))
          case _ => Left(s"Expect two U256 values for $name operator")
        }
      }

      override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
        argsType(0) match {
          case Type.U256 => Seq(instr)
          case _         => throw Compiler.Error(s"$name accepts U256 only", None)
        }
      }
    }
  }

  private def exp(
      i256Instr: ExpInstr[Val.I256],
      u256Instr: ExpInstr[Val.U256],
      i256Func: (I256, U256) => Option[I256],
      u256Func: (U256, U256) => Option[U256]
  ): ArithOperator =
    new ArithOperator {
      override def getReturnType(argsType: Seq[Type]): Seq[Type] = {
        if (argsType.length != 2 || !argsType(0).toVal.isNumeric || argsType(1) != Type.U256) {
          throw Compiler.Error(s"Invalid param types $argsType for ** operator", None)
        } else {
          Seq(argsType(0))
        }
      }

      def calc(values: Seq[Val]): Either[String, Val] = {
        values match {
          case Seq(left: Val.I256, right: Val.U256) =>
            i256Func(left.v, right.v).map(Val.I256(_)).toRight("I256 overflow")
          case Seq(left: Val.U256, right: Val.U256) =>
            u256Func(left.v, right.v).map(Val.U256(_)).toRight("U256 overflow")
          case _ => Left(s"Expect (I256, U256) or (U256, U256) for ** operator")
        }
      }

      override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
        argsType(0) match {
          case Type.I256 => Seq(i256Instr)
          case Type.U256 => Seq(u256Instr)
          case _         => throw Compiler.Error(s"Expect I256/U256 for ** operator", None)
        }
      }
    }

  val Add: ArithOperator = binary("+", I256Add, U256Add, _ add _, _ add _)
  val Sub: ArithOperator = binary("-", I256Sub, U256Sub, _ sub _, _ sub _)
  val Mul: ArithOperator = binary("*", I256Mul, U256Mul, _ mul _, _ mul _)
  val Exp: ArithOperator = exp(I256Exp, U256Exp, _ pow _, _ pow _)
  val Div: ArithOperator = binary("/", I256Div, U256Div, _ div _, _ div _)
  val Mod: ArithOperator = binary("%", I256Mod, U256Mod, _ mod _, _ mod _)

  val ModAdd: ArithOperator = u256Binary("ModAdd", U256ModAdd, _ modAdd _)
  val ModSub: ArithOperator = u256Binary("ModSub", U256ModSub, _ modSub _)
  val ModMul: ArithOperator = u256Binary("ModMul", U256ModMul, _ modMul _)
  val ModExp: ArithOperator = u256Binary("ModExp", U256ModExp, _ modPow _)
  val SHL: ArithOperator    = u256Binary("SHL", U256SHL, _ shl _)
  val SHR: ArithOperator    = u256Binary("SHR", U256SHR, _ shr _)
  val BitAnd: ArithOperator = u256Binary("BitAnd", U256BitAnd, _ bitAnd _)
  val BitOr: ArithOperator  = u256Binary("BitOr", U256BitOr, _ bitOr _)
  val Xor: ArithOperator    = u256Binary("Xor", U256Xor, _ xor _)

  val Concat: Operator = new Operator {
    def calc(values: Seq[Val]): Either[String, Val] = {
      values match {
        case Seq(left: Val.ByteVec, right: Val.ByteVec) =>
          Right(Val.ByteVec(left.bytes ++ right.bytes))
        case _ => Left(s"Expect two ByteVec values for ++ operator")
      }
    }

    override def getReturnType(argsType: Seq[Type]): Seq[Type] = {
      if (argsType.length != 2 || argsType(0) != Type.ByteVec || argsType(1) != Type.ByteVec) {
        throw Compiler.Error(s"Invalid param types $argsType for $this", None)
      } else {
        Seq(Type.ByteVec)
      }
    }

    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      Seq(ByteVecConcat)
    }
  }
}

sealed trait TestOperator extends Operator {
  def getReturnType(argsType: Seq[Type]): Seq[Type] = {
    if (argsType.length != 2 || argsType(0) != argsType(1) || !argsType(0).isPrimitive) {
      throw Compiler.Error(s"Invalid param types $argsType for $this", None)
    } else {
      Seq(Type.Bool)
    }
  }
}
object TestOperator {
  case object Eq extends TestOperator {
    def calc(values: Seq[Val]): Either[String, Val] = {
      values match {
        case Seq(left, right) => Right(Val.Bool(left == right))
        case _                => Left(s"Expect two values for == operator")
      }
    }

    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      argsType(0) match {
        case Type.I256    => Seq(I256Eq)
        case Type.U256    => Seq(U256Eq)
        case Type.Bool    => Seq(BoolEq)
        case Type.ByteVec => Seq(ByteVecEq)
        case Type.Address => Seq(AddressEq)
        case _ =>
          throw Compiler.Error("Expect I256/U256/Bool/ByteVec/Address for == operator", None)
      }
    }
  }
  case object Ne extends TestOperator {
    def calc(values: Seq[Val]): Either[String, Val] = {
      values match {
        case Seq(left, right) => Right(Val.Bool(left != right))
        case _                => Left(s"Expect two values for != operator")
      }
    }

    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      argsType(0) match {
        case Type.I256    => Seq(I256Neq)
        case Type.U256    => Seq(U256Neq)
        case Type.Bool    => Seq(BoolNeq)
        case Type.ByteVec => Seq(ByteVecNeq)
        case Type.Address => Seq(AddressNeq)
        case _ =>
          throw Compiler.Error("Expect I256/U256/Bool/ByteVec/Address for != operator", None)
      }
    }
  }

  private def inequality(
      operator: String,
      i256Instr: BinaryArithmeticInstr[Val.I256],
      u256Instr: BinaryArithmeticInstr[Val.U256],
      i256Func: (I256, I256) => Boolean,
      u256Func: (U256, U256) => Boolean
  ): TestOperator = {
    new TestOperator {
      def calc(values: Seq[Val]): Either[String, Val] = {
        values match {
          case Seq(left: Val.I256, right: Val.I256) =>
            Right(Val.Bool(i256Func(left.v, right.v)))
          case Seq(left: Val.U256, right: Val.U256) =>
            Right(Val.Bool(u256Func(left.v, right.v)))
          case _ => Left(s"Expect two I256 or two U256 values for $operator operator")
        }
      }

      override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
        argsType(0) match {
          case Type.I256 => Seq(i256Instr)
          case Type.U256 => Seq(u256Instr)
          case _         => throw Compiler.Error(s"Expect I256/U256 for $operator operator", None)
        }
      }
    }
  }

  val Lt: TestOperator = inequality("<", I256Lt, U256Lt, _ < _, _ < _)
  val Le: TestOperator = inequality("<=", I256Le, U256Le, _ <= _, _ <= _)
  val Gt: TestOperator = inequality(">", I256Gt, U256Gt, _ > _, _ > _)
  val Ge: TestOperator = inequality(">=", I256Ge, U256Ge, _ >= _, _ >= _)
}

sealed trait LogicalOperator extends TestOperator

object LogicalOperator {
  case object Not extends LogicalOperator {
    def calc(values: Seq[Val]): Either[String, Val] = {
      values match {
        case Seq(left: Val.Bool) => Right(Val.Bool(!left.v))
        case _                   => Left(s"Expect a Bool value for ! operator")
      }
    }

    override def getReturnType(argsType: Seq[Type]): Seq[Type] = {
      if (argsType.length != 1 || argsType(0) != Type.Bool) {
        throw Compiler.Error(s"Invalid param types $argsType for $this", None)
      } else {
        Seq(Type.Bool)
      }
    }

    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(BoolNot)
  }

  sealed trait BinaryLogicalOperator extends LogicalOperator {
    override def getReturnType(argsType: Seq[Type]): Seq[Type] = {
      if (argsType.length != 2 || argsType(0) != Type.Bool || argsType(1) != Type.Bool) {
        throw Compiler.Error(s"Invalid param types $argsType for $this", None)
      } else {
        Seq(Type.Bool)
      }
    }
  }
  case object And extends BinaryLogicalOperator {
    def calc(values: Seq[Val]): Either[String, Val] = {
      values match {
        case Seq(left: Val.Bool, right: Val.Bool) =>
          Right(Val.Bool(left.v && right.v))
        case _ => Left("Expect two Bool values for && operator")
      }
    }

    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(BoolAnd)
  }
  case object Or extends BinaryLogicalOperator {
    def calc(values: Seq[Val]): Either[String, Val] = {
      values match {
        case Seq(left: Val.Bool, right: Val.Bool) =>
          Right(Val.Bool(left.v || right.v))
        case _ => Left("Expect two Bool values for || operator")
      }
    }

    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(BoolOr)
  }
}
