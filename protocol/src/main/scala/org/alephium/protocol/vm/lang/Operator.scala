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
      throw Compiler.Error(s"Invalid param types $argsType for ArithOperator")
    } else {
      Seq(argsType(0))
    }
  }
}
object ArithOperator {
  private def binary(
      i256Instr: BinaryArithmeticInstr[Val.I256],
      u256Instr: BinaryArithmeticInstr[Val.U256]
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

  private def u256Binary(name: String, instr: BinaryArithmeticInstr[Val.U256]): ArithOperator = {
    new ArithOperator {
      override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
        argsType(0) match {
          case Type.U256 => Seq(instr)
          case _         => throw new RuntimeException(s"$name accepts U256 only")
        }
      }
    }
  }

  val Add: ArithOperator = binary(I256Add, U256Add)
  val Sub: ArithOperator = binary(I256Sub, U256Sub)
  val Mul: ArithOperator = binary(I256Mul, U256Mul)
  val Div: ArithOperator = binary(I256Div, U256Div)
  val Mod: ArithOperator = binary(I256Mod, U256Mod)

  val ModAdd: ArithOperator = u256Binary("ModAdd", U256ModAdd)
  val ModSub: ArithOperator = u256Binary("ModSub", U256ModSub)
  val ModMul: ArithOperator = u256Binary("ModMul", U256ModMul)
  val SHL: ArithOperator    = u256Binary("SHL", U256SHL)
  val SHR: ArithOperator    = u256Binary("SHR", U256SHR)
  val BitAnd: ArithOperator = u256Binary("BitAnd", U256BitAnd)
  val BitOr: ArithOperator  = u256Binary("BitOr", U256BitOr)
  val Xor: ArithOperator    = u256Binary("Xor", U256Xor)

  val Concat: Operator = new Operator {
    override def getReturnType(argsType: Seq[Type]): Seq[Type] = {
      if (argsType.length != 2 || argsType(0) != Type.ByteVec || argsType(1) != Type.ByteVec) {
        throw Compiler.Error(s"Invalid param types $argsType for $this")
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
    if (argsType.length != 2 || argsType(0) != argsType(1) || argsType(0).isArrayType) {
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
        case Type.I256    => Seq(I256Eq)
        case Type.U256    => Seq(U256Eq)
        case Type.Bool    => Seq(BoolEq)
        case Type.ByteVec => Seq(ByteVecEq)
        case Type.Address => Seq(AddressEq)
        case _            => throw new RuntimeException("Dead branch")
      }
    }
  }
  case object Ne extends TestOperator {
    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      argsType(0) match {
        case Type.I256    => Seq(I256Neq)
        case Type.U256    => Seq(U256Neq)
        case Type.Bool    => Seq(BoolNeq)
        case Type.ByteVec => Seq(ByteVecNeq)
        case Type.Address => Seq(AddressNeq)
        case _            => throw new RuntimeException("Dead branch")
      }
    }
  }

  private def inequality(
      i256Instr: BinaryArithmeticInstr[Val.I256],
      u256Instr: BinaryArithmeticInstr[Val.U256]
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

  val Lt: TestOperator = inequality(I256Lt, U256Lt)
  val Le: TestOperator = inequality(I256Le, U256Le)
  val Gt: TestOperator = inequality(I256Gt, U256Gt)
  val Ge: TestOperator = inequality(I256Ge, U256Ge)
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

    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(BoolNot)
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
    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(BoolAnd)
  }
  case object Or extends BinaryLogicalOperator {
    override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(BoolOr)
  }
}
