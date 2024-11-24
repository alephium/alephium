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

trait CompoundAssignmentOperator {
  def operatorName: String
  def i256Instr: BinaryArithmeticInstr[Val.I256]
  def u256Instr: BinaryArithmeticInstr[Val.U256]

  def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(i256Instr)
      case Type.U256 => Seq(u256Instr)
      case _         => throw Compiler.Error(s"Expect I256/U256 for $operatorName operator", None)
    }
  }
}

object CompoundAssignmentOperator {
  val AddAssign: CompoundAssignmentOperator = new CompoundAssignmentOperator {
    def operatorName: String = "+="
    def i256Instr: BinaryArithmeticInstr[Val.I256] = I256Add
    def u256Instr: BinaryArithmeticInstr[Val.U256] = U256Add
  }

  val SubAssign: CompoundAssignmentOperator = new CompoundAssignmentOperator {
    def operatorName: String = "-="
    def i256Instr: BinaryArithmeticInstr[Val.I256] = I256Sub
    def u256Instr: BinaryArithmeticInstr[Val.U256] = U256Sub
  }

  val MulAssign: CompoundAssignmentOperator = new CompoundAssignmentOperator {
    def operatorName: String = "*="
    def i256Instr: BinaryArithmeticInstr[Val.I256] = I256Mul
    def u256Instr: BinaryArithmeticInstr[Val.U256] = U256Mul
  }

  val DivAssign: CompoundAssignmentOperator = new CompoundAssignmentOperator {
    def operatorName: String = "/="
    def i256Instr: BinaryArithmeticInstr[Val.I256] = I256Div
    def u256Instr: BinaryArithmeticInstr[Val.U256] = U256Div
  }

  val values: Seq[CompoundAssignmentOperator] = Seq(AddAssign, SubAssign, MulAssign, DivAssign)
}
