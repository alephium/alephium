package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm._

sealed trait Operator {
  def getReturnType(argsType: Seq[Val.Type]): Seq[Val.Type]
  def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]]
}

sealed trait ArithOperator extends Operator {
  def getReturnType(argsType: Seq[Val.Type]): Seq[Val.Type] = {
    if (argsType.length != 2 || argsType(0) != argsType(1) || !Val.Type.isNumeric(argsType(0))) {
      throw Checker.Error(s"Invalid param types $argsType for $this")
    } else Seq(argsType(0))
  }
}
case object Add extends ArithOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(I64Add)
      case Val.U64  => Seq(U64Add)
      case Val.I256 => Seq(I256Add)
      case Val.U256 => Seq(U256Add)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}
case object Sub extends ArithOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(I64Sub)
      case Val.U64  => Seq(U64Sub)
      case Val.I256 => Seq(I256Sub)
      case Val.U256 => Seq(U256Sub)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}
case object Mul extends ArithOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(I64Mul)
      case Val.U64  => Seq(U64Mul)
      case Val.I256 => Seq(I256Mul)
      case Val.U256 => Seq(U256Mul)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}
case object Div extends ArithOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(I64Div)
      case Val.U64  => Seq(U64Div)
      case Val.I256 => Seq(I256Div)
      case Val.U256 => Seq(U256Div)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}
case object Mod extends ArithOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(I64Mod)
      case Val.U64  => Seq(U64Mod)
      case Val.I256 => Seq(I256Mod)
      case Val.U256 => Seq(U256Mod)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}

sealed trait TestOperator extends Operator {
  def getReturnType(argsType: Seq[Val.Type]): Seq[Val.Type] = {
    if (argsType.length != 2 || argsType(0) != argsType(1) || !Val.Type.isNumeric(argsType(0))) {
      throw Checker.Error(s"Invalid param types $argsType for $this")
    } else Seq(Val.Bool)
  }

  def toBranchIR(left: Seq[Val.Type], offset: Byte): Seq[Instr[StatelessContext]]
}
case object Eq extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(EqI64)
      case Val.U64  => Seq(EqU64)
      case Val.I256 => Seq(EqI256)
      case Val.U256 => Seq(EqU256)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
  override def toBranchIR(left: Seq[Val.Type], offset: Byte): Seq[Instr[StatelessContext]] = {
    left(0) match {
      case Val.I64  => Seq(IfNeI64(offset))
      case Val.U64  => Seq(IfNeU64(offset))
      case Val.I256 => Seq(IfNeI256(offset))
      case Val.U256 => Seq(IfNeU256(offset))
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}
case object Ne extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(NeI64)
      case Val.U64  => Seq(NeU64)
      case Val.I256 => Seq(NeI256)
      case Val.U256 => Seq(NeU256)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
  override def toBranchIR(left: Seq[Val.Type], offset: Byte): Seq[Instr[StatelessContext]] = {
    left(0) match {
      case Val.I64  => Seq(IfEqI64(offset))
      case Val.U64  => Seq(IfEqU64(offset))
      case Val.I256 => Seq(IfEqI256(offset))
      case Val.U256 => Seq(IfEqU256(offset))
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}
case object Lt extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(LtI64)
      case Val.U64  => Seq(LtU64)
      case Val.I256 => Seq(LtI256)
      case Val.U256 => Seq(LtU256)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
  override def toBranchIR(left: Seq[Val.Type], offset: Byte): Seq[Instr[StatelessContext]] = {
    left(0) match {
      case Val.I64  => Seq(IfGeI64(offset))
      case Val.U64  => Seq(IfGeU64(offset))
      case Val.I256 => Seq(IfGeI256(offset))
      case Val.U256 => Seq(IfGeU256(offset))
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}
case object Le extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(LeI64)
      case Val.U64  => Seq(LeU64)
      case Val.I256 => Seq(LeI256)
      case Val.U256 => Seq(LeU256)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
  override def toBranchIR(left: Seq[Val.Type], offset: Byte): Seq[Instr[StatelessContext]] = {
    left(0) match {
      case Val.I64  => Seq(IfGtI64(offset))
      case Val.U64  => Seq(IfGtU64(offset))
      case Val.I256 => Seq(IfGtI256(offset))
      case Val.U256 => Seq(IfGtU256(offset))
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}
case object Gt extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(GtI64)
      case Val.U64  => Seq(GtU64)
      case Val.I256 => Seq(GtI256)
      case Val.U256 => Seq(GtU256)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
  override def toBranchIR(left: Seq[Val.Type], offset: Byte): Seq[Instr[StatelessContext]] = {
    left(0) match {
      case Val.I64  => Seq(IfLeI64(offset))
      case Val.U64  => Seq(IfLeU64(offset))
      case Val.I256 => Seq(IfLeI256(offset))
      case Val.U256 => Seq(IfLeU256(offset))
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}
case object Ge extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    argsType(0) match {
      case Val.I64  => Seq(GeI64)
      case Val.U64  => Seq(GeU64)
      case Val.I256 => Seq(GeI256)
      case Val.U256 => Seq(GeU256)
      case _        => throw new RuntimeException("Dead branch")
    }
  }
  override def toBranchIR(left: Seq[Val.Type], offset: Byte): Seq[Instr[StatelessContext]] = {
    left(0) match {
      case Val.I64  => Seq(IfLtI64(offset))
      case Val.U64  => Seq(IfLtU64(offset))
      case Val.I256 => Seq(IfLtI256(offset))
      case Val.U256 => Seq(IfLtU256(offset))
      case _        => throw new RuntimeException("Dead branch")
    }
  }
}
