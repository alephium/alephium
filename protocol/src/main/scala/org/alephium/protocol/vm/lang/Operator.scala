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
}
case object Eq extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    ???
  }
}
case object Ne extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    ???
  }
}
case object Lt extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    ???
  }
}
case object Le extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    ???
  }
}
case object Gt extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    ???
  }
}
case object Ge extends TestOperator {
  override def toIR(argsType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
    ???
  }
}
