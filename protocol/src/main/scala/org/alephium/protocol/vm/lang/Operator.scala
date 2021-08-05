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

package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm._

sealed trait Operator {
  def getReturnType(argsType: Seq[Type]): Seq[Type]
  def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]]
}

sealed trait ArithOperator extends Operator {
  def getReturnType(argsType: Seq[Type]): Seq[Type] = {
    if (argsType.length != 2 || argsType(0) != argsType(1) || !argsType(0).toVal.isNumeric) {
      throw Compiler.Error(s"Invalid param types $argsType for $this")
    } else {
      Seq(argsType(0))
    }
  }
}
object ArithOperator {
  private def binary(
      i256Instr: BinaryArithmeticInstr,
      u256Instr: BinaryArithmeticInstr
  ): ArithOperator = {
    new ArithOperator {
      override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
        argsType(0) match {
          case Type.I256 => Seq(i256Instr)
          case Type.U256 => Seq(u256Instr)
          case _         => throw new RuntimeException("Dead branch")
        }
      }
    }
  }

  private def u256Binary(name: String, instr: BinaryArithmeticInstr): ArithOperator = {
    new ArithOperator {
      override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
        argsType(0) match {
          case Type.U256 => Seq(instr)
          case _         => throw new RuntimeException(s"$name accepts U256 only")
        }
      }
    }
  }

  val Add = binary(I256Add, U256Add)
  val Sub = binary(I256Sub, U256Sub)
  val Mul = binary(I256Mul, U256Mul)
  val Div = binary(I256Div, U256Div)
  val Mod = binary(I256Mod, U256Mod)

  val ModAdd = u256Binary("ModAdd", U256ModAdd)
  val ModSub = u256Binary("ModSub", U256ModSub)
  val ModMul = u256Binary("ModMul", U256ModMul)
  val SHL    = u256Binary("SHL", U256SHL)
  val SHR    = u256Binary("SHR", U256SHR)
  val BitAnd = u256Binary("BitAnd", U256BitAnd)
  val BitOr  = u256Binary("BitOr", U256BitOr)
  val Xor    = u256Binary("Xor", U256Xor)
}

sealed trait TestOperator extends Operator {
  def getReturnType(argsType: Seq[Type]): Seq[Type] = {
    if (argsType.length != 2 || argsType(0) != argsType(1)) {
      throw Compiler.Error(s"Invalid param types $argsType for $this")
    } else {
      Seq(Type.Bool)
    }
  }
}
object TestOperator {
  case object Eq extends TestOperator {
    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      argsType(0) match {
        case Type.I256    => Seq(EqI256)
        case Type.U256    => Seq(EqU256)
        case Type.Bool    => Seq(EqBool)
        case Type.ByteVec => Seq(EqByteVec)
        case Type.Address => Seq(EqAddress)
        case _            => throw new RuntimeException("Dead branch")
      }
    }
  }

  private def inequality(
      i256Instr: BinaryArithmeticInstr,
      u256Instr: BinaryArithmeticInstr
  ): TestOperator = {
    new TestOperator {
      override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
        argsType(0) match {
          case Type.I256 => Seq(i256Instr)
          case Type.U256 => Seq(u256Instr)
          case _         => throw new RuntimeException("Expect I256/U256 for inequality test")
        }
      }
    }
  }

  val Ne = inequality(NeI256, NeU256)
  val Lt = inequality(LtI256, LtU256)
  val Le = inequality(LeI256, LeU256)
  val Gt = inequality(GtI256, GtU256)
  val Ge = inequality(GeI256, GeU256)
}

sealed trait LogicalOperator extends TestOperator

object LogicalOperator {
  case object Not extends LogicalOperator {
    override def getReturnType(argsType: Seq[Type]): Seq[Type] = {
      if (argsType.length != 1 || argsType(0) != Type.Bool) {
        throw Compiler.Error(s"Invalid param types $argsType for $this")
      } else {
        Seq(Type.Bool)
      }
    }

    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(NotBool)
  }

  sealed trait BinaryLogicalOperator extends LogicalOperator {
    override def getReturnType(argsType: Seq[Type]): Seq[Type] = {
      if (argsType.length != 2 || argsType(0) != Type.Bool || argsType(1) != Type.Bool) {
        throw Compiler.Error(s"Invalid param types $argsType for $this")
      } else {
        Seq(Type.Bool)
      }
    }
  }
  case object And extends BinaryLogicalOperator {
    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(AndBool)
  }
  case object Or extends BinaryLogicalOperator {
    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(OrBool)
  }
}
