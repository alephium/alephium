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
case object Add extends ArithOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(I256Add)
      case Type.U256 => Seq(U256Add)
      case _         => throw new RuntimeException("Dead branch")
    }
  }
}
case object Sub extends ArithOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(I256Sub)
      case Type.U256 => Seq(U256Sub)
      case _         => throw new RuntimeException("Dead branch")
    }
  }
}
case object Mul extends ArithOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(I256Mul)
      case Type.U256 => Seq(U256Mul)
      case _         => throw new RuntimeException("Dead branch")
    }
  }
}
case object Div extends ArithOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(I256Div)
      case Type.U256 => Seq(U256Div)
      case _         => throw new RuntimeException("Dead branch")
    }
  }
}
case object Mod extends ArithOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(I256Mod)
      case Type.U256 => Seq(U256Mod)
      case _         => throw new RuntimeException("Dead branch")
    }
  }
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
case object Eq extends TestOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256    => Seq(EqI256)
      case Type.U256    => Seq(EqU256)
      case Type.ByteVec => Seq(EqByteVec)
      case Type.Address => Seq(EqAddress)
      case _            => throw new RuntimeException("Dead branch")
    }
  }
}
case object Ne extends TestOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(NeI256)
      case Type.U256 => Seq(NeU256)
      case _         => throw new RuntimeException("Dead branch")
    }
  }
}
case object Lt extends TestOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(LtI256)
      case Type.U256 => Seq(LtU256)
      case _         => throw new RuntimeException("Dead branch")
    }
  }
}
case object Le extends TestOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(LeI256)
      case Type.U256 => Seq(LeU256)
      case _         => throw new RuntimeException("Dead branch")
    }
  }
}
case object Gt extends TestOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(GtI256)
      case Type.U256 => Seq(GtU256)
      case _         => throw new RuntimeException("Dead branch")
    }
  }
}
case object Ge extends TestOperator {
  override def genCode(argsType: Seq[Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Type.I256 => Seq(GeI256)
      case Type.U256 => Seq(GeU256)
      case _         => throw new RuntimeException("Dead branch")
    }
  }
}

sealed trait LogicalOperator extends TestOperator
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
