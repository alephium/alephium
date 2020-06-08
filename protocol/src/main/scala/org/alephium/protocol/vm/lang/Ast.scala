package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.Val

object Ast {
  case class Ident(name: String)
  case class Argument(ident: Ident, tpe: Val.Type)
  case class Return(exprs: Seq[Expr])

  sealed trait Operator
  case object Add extends Operator
  case object Sub extends Operator
  case object Mul extends Operator
  case object Div extends Operator
  case object Mod extends Operator

  sealed trait Expr {
    def getType(ctx: Checker.Ctx): Seq[Val.Type]
  }
  case class Const(v: Val) extends Expr {
    override def getType(ctx: Checker.Ctx): Seq[Val.Type] = Seq(v.tpe)
  }
  case class Variable(id: Ident) extends Expr {
    override def getType(ctx: Checker.Ctx): Seq[Val.Type] = Seq(ctx.getType(id))
  }
  case class Binop(op: Operator, left: Expr, right: Expr) extends Expr {
    override def getType(ctx: Checker.Ctx): Seq[Val.Type] = {
      val leftType  = left.getType(ctx: Checker.Ctx)
      val rightType = right.getType(ctx: Checker.Ctx)
      if (leftType.length == 1 && leftType == rightType) leftType
      else throw Checker.Error(s"Type error for $op, left: $leftType, right: $rightType")
    }
  }
  case class Call(id: Ident, args: Seq[Expr]) extends Expr {
    override def getType(ctx: Checker.Ctx): Seq[Val.Type] = ???
  }
  case class ParenExpr(expr: Expr) extends Expr {
    override def getType(ctx: Checker.Ctx): Seq[Val.Type] = expr.getType(ctx: Checker.Ctx)
  }

  sealed trait Statement {
    def checkType(ctx: Checker.Ctx): Unit
  }
  case class VarDef(isMutable: Boolean, variable: Ident, value: Expr) extends Statement {
    override def checkType(ctx: Checker.Ctx): Unit =
      ctx.addVariable(variable, value.getType(ctx: Checker.Ctx), isMutable)
  }
  case class FuncDef(name: Ident,
                     args: Seq[Argument],
                     rtypes: Seq[Val.Type],
                     body: Seq[Statement],
                     rStmt: Return)
      extends Statement {
    override def checkType(ctx: Checker.Ctx): Unit = {
      args.foreach(arg => ctx.addVariable(arg.ident, arg.tpe, isMutable = false))
      body.foreach(_.checkType(ctx))
      val returnTypes = rStmt.exprs.flatMap(_.getType(ctx: Checker.Ctx))
      if (!returnTypes.equals(rtypes))
        throw Checker.Error(s"Invalid return types: expected $rtypes, got $returnTypes")
    }
  }
  case class Assign(target: Ident, rhs: Expr) extends Statement {
    override def checkType(ctx: Checker.Ctx): Unit = {
      ctx.check(target, rhs.getType(ctx: Checker.Ctx))
    }
  }

  case class Contract(assigns: Seq[VarDef], funcs: Seq[FuncDef]) {
    def check(): Unit = {
      val ctx = Checker.Ctx.empty
      assigns.foreach(_.checkType(ctx))
      funcs.foreach { func =>
        val localCtx = ctx.branch()
        func.checkType(localCtx)
      }
    }
  }
}
