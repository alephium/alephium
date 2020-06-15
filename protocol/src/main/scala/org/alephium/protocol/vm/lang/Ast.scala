package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm._
import org.alephium.util.AVector

object Ast {
  final case class Ident(name: String)
  final case class CallId(name: String, isBuiltIn: Boolean)
  final case class Argument(ident: Ident, tpe: Val.Type, isMutable: Boolean)

  sealed trait Expr {
    var tpe: Option[Seq[Val.Type]] = None
    protected def _getType(ctx: Checker.Ctx): Seq[Val.Type]
    def getType(ctx: Checker.Ctx): Seq[Val.Type] = tpe match {
      case Some(ts) => ts
      case None =>
        val t = _getType(ctx)
        tpe = Some(t)
        t
    }
    def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]]
  }
  final case class Const(v: Val) extends Expr {
    override def _getType(ctx: Checker.Ctx): Seq[Val.Type] = Seq(v.tpe)

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      v match {
        case Val.Bool(b)      => Seq(if (b) BoolConstTrue else BoolConstFalse)
        case _: Val.Byte      => ???
        case u: Val.I64       => Seq(ConstInstr.i64(u))
        case u: Val.U64       => Seq(ConstInstr.u64(u))
        case u: Val.I256      => Seq(ConstInstr.i256(u))
        case u: Val.U256      => Seq(ConstInstr.u256(u))
        case _: Val.Byte32    => ???
        case _: Val.BoolVec   => ???
        case _: Val.ByteVec   => ???
        case _: Val.I64Vec    => ???
        case _: Val.U64Vec    => ???
        case _: Val.I256Vec   => ???
        case _: Val.U256Vec   => ???
        case _: Val.Byte32Vec => ???
      }
    }
  }
  final case class Variable(id: Ident) extends Expr {
    override def _getType(ctx: Checker.Ctx): Seq[Val.Type] = Seq(ctx.getType(id))

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      val varInfo = ctx.getVariable(id)
      if (ctx.isField(id)) Seq(LoadField(varInfo.index))
      else Seq(LoadLocal(varInfo.index))
    }
  }
  final case class Binop(op: Operator, left: Expr, right: Expr) extends Expr {
    override def _getType(ctx: Checker.Ctx): Seq[Val.Type] = {
      op.getReturnType(left.getType(ctx) ++ right.getType(ctx))
    }

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      left.toIR(ctx) ++ right.toIR(ctx) ++ op.toIR(left.getType(ctx) ++ right.getType(ctx))
    }
  }
  final case class Call(id: CallId, args: Seq[Expr]) extends Expr {
    override def _getType(ctx: Checker.Ctx): Seq[Val.Type] = {
      val funcInfo = ctx.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(ctx)))
    }

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      args.flatMap(_.toIR(ctx)) ++ ctx.getFunc(id).toIR(args.flatMap(_.getType(ctx)))
    }
  }
  final case class ParenExpr(expr: Expr) extends Expr {
    override def _getType(ctx: Checker.Ctx): Seq[Val.Type] = expr.getType(ctx: Checker.Ctx)

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = expr.toIR(ctx)
  }

  sealed trait Statement {
    def check(ctx: Checker.Ctx): Unit
    def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]]
  }
  final case class VarDef(isMutable: Boolean, ident: Ident, value: Expr) extends Statement {
    override def check(ctx: Checker.Ctx): Unit =
      ctx.addVariable(ident, value.getType(ctx: Checker.Ctx), isMutable)

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      value.toIR(ctx) :+ ctx.toIR(ident)
    }
  }
  final case class FuncDef(ident: Ident,
                           args: Seq[Argument],
                           rtypes: Seq[Val.Type],
                           body: Seq[Statement]) {
    def check(ctx: Checker.Ctx): Unit = {
      ctx.setFuncScope(ident)
      args.foreach(arg => ctx.addVariable(arg.ident, arg.tpe, isMutable = false))
      body.foreach(_.check(ctx))
    }

    def toMethod(ctx: Checker.Ctx): Method[StatelessContext] = {
      ctx.setFuncScope(ident)
      val localVars  = ctx.getLocalVars(ident)
      val localsType = localVars.map(_.tpe)
      val instrs     = body.flatMap(_.toIR(ctx))
      Method[StatelessContext](AVector.from(localsType), AVector.from(instrs))
    }
  }
  final case class Assign(target: Ident, rhs: Expr) extends Statement {
    override def check(ctx: Checker.Ctx): Unit = {
      ctx.checkAssign(target, rhs.getType(ctx: Checker.Ctx))
    }

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      rhs.toIR(ctx) :+ ctx.toIR(target)
    }
  }
  final case class FuncCall(id: CallId, args: Seq[Expr]) extends Statement {
    override def check(ctx: Checker.Ctx): Unit = {
      val funcInfo = ctx.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(ctx)))
      ()
    }

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      val func       = ctx.getFunc(id)
      val argsType   = args.flatMap(_.getType(ctx))
      val returnType = func.getReturnType(argsType)
      args.flatMap(_.toIR(ctx)) ++ func.toIR(argsType) ++ Seq.fill(returnType.length)(Pop)
    }
  }
  final case class IfElse(condition: Expr, ifBranch: Seq[Statement], elseBranch: Seq[Statement])
      extends Statement {
    override def check(ctx: Checker.Ctx): Unit = {
      if (condition.getType(ctx) != Seq(Val.Bool)) {
        throw Checker.Error(s"Invalid type of condition expr $condition")
      } else {
        ifBranch.foreach(_.check(ctx))
        elseBranch.foreach(_.check(ctx))
      }
    }

    override def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] = {
      val elseIRs = elseBranch.flatMap(_.toIR(ctx))
      val ifIRs   = ifBranch.flatMap(_.toIR(ctx)) :+ Offset(elseIRs.length.toByte)
      if (ifIRs.length > 0xFF || elseIRs.length > 0xFF) {
        // TODO: support long branches
        throw Checker.Error(s"Too many instrs for if-else branches")
      }
      val opIR = condition match {
        case Binop(Eq, left, right) =>
          left.toIR(ctx) ++ right.toIR(ctx) :+ IfNeU64(ifIRs.length.toByte)
        case Binop(Ne, left, right) =>
          left.toIR(ctx) ++ right.toIR(ctx) :+ IfEqU64(ifIRs.length.toByte)
        case Binop(Lt, left, right) =>
          left.toIR(ctx) ++ right.toIR(ctx) :+ IfGeU64(ifIRs.length.toByte)
        case Binop(Le, left, right) =>
          left.toIR(ctx) ++ right.toIR(ctx) :+ IfGtU64(ifIRs.length.toByte)
        case Binop(Gt, left, right) =>
          left.toIR(ctx) ++ right.toIR(ctx) :+ IfLeU64(ifIRs.length.toByte)
        case Binop(Ge, left, right) =>
          left.toIR(ctx) ++ right.toIR(ctx) :+ IfLtU64(ifIRs.length.toByte)
        case _ => ???
      }
      opIR ++ ifIRs ++ elseIRs
    }
  }
  final case class Return(exprs: Seq[Expr]) extends Statement {
    override def check(ctx: Checker.Ctx): Unit = {
      ctx.checkReturn(exprs.flatMap(_.getType(ctx)))
    }
    def toIR(ctx: Checker.Ctx): Seq[Instr[StatelessContext]] =
      exprs.flatMap(_.toIR(ctx)) ++ (if (exprs.isEmpty) Seq() else Seq(U64Return))
  }

  final case class Contract(ident: Ident, fields: Seq[Argument], funcs: Seq[FuncDef]) {
    def check(): Checker.Ctx = {
      val ctx = Checker.Ctx.empty
      fields.foreach(field => ctx.addVariable(field.ident, field.tpe, field.isMutable))
      ctx.addFuncDefs(funcs)
      funcs.foreach(_.check(ctx))
      ctx
    }

    def toIR(ctx: Checker.Ctx): StatelessScript = {
      val fieldsTypes = AVector.from(fields.map(assign => ctx.getType(assign.ident)))
      val methods     = AVector.from(funcs.map(func    => func.toMethod(ctx)))
      StatelessScript(fieldsTypes, methods)
    }
  }
}
