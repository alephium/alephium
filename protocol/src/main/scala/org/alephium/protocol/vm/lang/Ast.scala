package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm._
import org.alephium.util.AVector

object Ast {
  case class Ident(name: String)
  case class Argument(ident: Ident, tpe: Val.Type)
  case class Return(exprs: Seq[Expr]) {
    def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] =
      exprs.flatMap(_.toIR(ctx)) :+ U64Return
  }

  sealed trait Operator {
    def toIR: Seq[Instr[StatelessContext]]
  }
  case object Add extends Operator {
    override def toIR: Seq[Instr[StatelessContext]] = Seq(U64Add)
  }
  case object Sub extends Operator {
    override def toIR: Seq[Instr[StatelessContext]] = Seq(U64Sub)
  }
  case object Mul extends Operator {
    override def toIR: Seq[Instr[StatelessContext]] = Seq(U64Mul)
  }
  case object Div extends Operator {
    override def toIR: Seq[Instr[StatelessContext]] = Seq(U64Div)
  }
  case object Mod extends Operator {
    override def toIR: Seq[Instr[StatelessContext]] = Seq(U64Mod)
  }

  sealed trait Expr {
    def getType(ctx: Checker.Ctx): Seq[Val.Type]
    def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]]
  }
  case class Const(v: Val) extends Expr {
    override def getType(ctx: Checker.Ctx): Seq[Val.Type] = Seq(v.tpe)

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      v match {
        case n: Val.U64 => Seq(U64Const(n))
        case _          => ???
      }
    }
  }
  case class Variable(id: Ident) extends Expr {
    override def getType(ctx: Checker.Ctx): Seq[Val.Type] = Seq(ctx.getType(id))

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      val varInfo = ctx.getVariable(id)
      if (ctx.isField(id)) Seq(LoadField(varInfo.index.toByte))
      else Seq(LoadLocal(varInfo.index.toByte))
    }
  }
  case class Binop(op: Operator, left: Expr, right: Expr) extends Expr {
    override def getType(ctx: Checker.Ctx): Seq[Val.Type] = {
      val leftType  = left.getType(ctx: Checker.Ctx)
      val rightType = right.getType(ctx: Checker.Ctx)
      if (leftType.length == 1 && leftType == rightType) leftType
      else throw Checker.Error(s"Type error for $op, left: $leftType, right: $rightType")
    }

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      left.toIR(ctx) ++ right.toIR(ctx) ++ op.toIR
    }
  }
  case class Call(id: Ident, args: Seq[Expr]) extends Expr {
    override def getType(ctx: Checker.Ctx): Seq[Val.Type] = ???

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = ???
  }
  case class ParenExpr(expr: Expr) extends Expr {
    override def getType(ctx: Checker.Ctx): Seq[Val.Type] = expr.getType(ctx: Checker.Ctx)

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = expr.toIR(ctx)
  }

  sealed trait Statement {
    def check(ctx: Checker.Ctx): Unit
    def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]]
  }
  case class VarDef(isMutable: Boolean, ident: Ident, value: Expr) extends Statement {
    override def check(ctx: Checker.Ctx): Unit =
      ctx.addVariable(ident, value.getType(ctx: Checker.Ctx), isMutable)

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      value.toIR(ctx) :+ ctx.toIR(ident)
    }
  }
  case class FuncDef(ident: Ident,
                     args: Seq[Argument],
                     rtypes: Seq[Val.Type],
                     body: Seq[Statement],
                     rStmt: Return)
      extends Statement {
    override def check(ctx: Checker.Ctx): Unit = {
      ctx.setScope(ident)
      args.foreach(arg => ctx.addVariable(arg.ident, arg.tpe, isMutable = false))
      body.foreach(_.check(ctx))
      val returnTypes = rStmt.exprs.flatMap(_.getType(ctx: Checker.Ctx))
      if (!returnTypes.equals(rtypes))
        throw Checker.Error(s"Invalid return types: expected $rtypes, got $returnTypes")
    }

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      ???
    }

    def toMethod(ctx: Checker.Ctx): Method[StatelessContext] = {
      val localVars  = ctx.getLocalVars(ident)
      val localsType = localVars.map(_.tpe)
      val instrs     = body.flatMap(_.toIR(ctx)) ++ rStmt.toIR(ctx)
      Method[StatelessContext](AVector.from(localsType), AVector.from(instrs))
    }
  }
  case class Assign(target: Ident, rhs: Expr) extends Statement {
    override def check(ctx: Checker.Ctx): Unit = {
      ctx.checkAssign(target, rhs.getType(ctx: Checker.Ctx))
    }

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      rhs.toIR(ctx) :+ ctx.toIR(target)
    }
  }

  case class Contract(assigns: Seq[VarDef], funcs: Seq[FuncDef]) {
    def check(): Checker.Ctx = {
      val ctx = Checker.Ctx.empty
      assigns.foreach(_.check(ctx))
      funcs.foreach { func =>
        func.check(ctx)
      }
      ctx
    }

    def toIR(ctx: Checker.Ctx): StatelessScript = {
      val fieldsTypes = AVector.from(assigns.map(assign => ctx.getType(assign.ident)))
      val methods     = AVector.from(funcs.map(func     => func.toMethod(ctx)))
      StatelessScript(fieldsTypes, methods)
    }
  }
}
