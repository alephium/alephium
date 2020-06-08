package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.Val

object Ast {
  case class Ident(name: String)

  sealed trait Operator
  case object Add extends Operator
  case object Sub extends Operator
  case object Mul extends Operator
  case object Div extends Operator
  case object Mod extends Operator

  sealed trait Expr {
    def tpe: Val.Type
  }
  case class Const(v: Val) extends Expr {
    override def tpe: Val.Type = v.tpe
  }
  case class Variable(id: Ident, var tpeOpt: Option[Val.Type], var isMutableOpt: Option[Boolean])
      extends Expr {
    override def tpe: Val.Type = tpeOpt match {
      case Some(t) => t
      case None    => throw Validator.Error(s"Unknown type for variable $id")
    }

    def setType(tpe: Val.Type): Unit = tpeOpt match {
      case Some(t) =>
        if (tpe != t) throw Validator.Error(s"Conflict types for $id: old $t, new $tpe")
      case None => tpeOpt = Some(tpe)
    }
  }
  case class Binop(op: Operator, left: Expr, right: Expr) extends Expr {
    override def tpe: Val.Type = {
      val leftType  = left.tpe
      val rightType = right.tpe
      if (leftType == rightType) leftType
      else throw Validator.Error(s"Type error for $op, left: $leftType, right: $rightType")
    }
  }
  case class Call(id: Ident, args: Seq[Expr]) extends Expr {
    override def tpe: Val.Type = ???
  }
  case class ParenExpr(expr: Expr) extends Expr {
    override def tpe: Val.Type = expr.tpe
  }

  sealed trait Statement {
    def inferType(): Unit
  }
  case class VarDef(variable: Variable, value: Expr) extends Statement {
    override def inferType(): Unit = variable.setType(value.tpe)
  }
  case class FuncDef(name: Ident, args: Seq[Variable], rtypes: Seq[Val.Type], body: Seq[Statement])
      extends Statement {
    override def inferType(): Unit = {
      body.foreach(_.inferType())
    }
  }
  case class Assign(target: Variable, rhs: Expr) extends Statement {
    override def inferType(): Unit = target.setType(rhs.tpe)
  }
  case class Return(vals: Seq[Expr]) extends Statement {
    override def inferType(): Unit = ()
  }

  case class Contract(assigns: Seq[VarDef], funcs: Seq[FuncDef]) {
    def inferType(): Unit = {
      assigns.foreach(_.inferType())
      funcs.foreach(_.inferType())
    }
  }
}
