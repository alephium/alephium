package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm._
import org.alephium.util.AVector

object Ast {
  final case class Ident(name: String)
  final case class CallId(name: String, isBuiltIn: Boolean)
  final case class Argument(ident: Ident, tpe: Val.Type, isMutable: Boolean)

  sealed trait Expr {
    var tpe: Option[Seq[Val.Type]] = None
    protected def _getType(ctx: Compiler.Ctx): Seq[Val.Type]
    def getType(ctx: Compiler.Ctx): Seq[Val.Type] = tpe match {
      case Some(ts) => ts
      case None =>
        val t = _getType(ctx)
        tpe = Some(t)
        t
    }
    def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]]
  }
  final case class Const(v: Val) extends Expr {
    override def _getType(ctx: Compiler.Ctx): Seq[Val.Type] = Seq(v.tpe)

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
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
    override def _getType(ctx: Compiler.Ctx): Seq[Val.Type] = Seq(ctx.getType(id))

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      val varInfo = ctx.getVariable(id)
      if (ctx.isField(id)) Seq(LoadField(varInfo.index))
      else Seq(LoadLocal(varInfo.index))
    }
  }
  final case class UnaryOp(op: Operator, expr: Expr) extends Expr {
    override def _getType(ctx: Compiler.Ctx): Seq[Val.Type] = {
      op.getReturnType(expr.getType(ctx))
    }

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      expr.toIR(ctx) ++ op.toIR(expr.getType(ctx))
    }
  }
  final case class Binop(op: Operator, left: Expr, right: Expr) extends Expr {
    override def _getType(ctx: Compiler.Ctx): Seq[Val.Type] = {
      op.getReturnType(left.getType(ctx) ++ right.getType(ctx))
    }

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      left.toIR(ctx) ++ right.toIR(ctx) ++ op.toIR(left.getType(ctx) ++ right.getType(ctx))
    }
  }
  final case class Call(id: CallId, args: Seq[Expr]) extends Expr {
    override def _getType(ctx: Compiler.Ctx): Seq[Val.Type] = {
      val funcInfo = ctx.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(ctx)))
    }

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      args.flatMap(_.toIR(ctx)) ++ ctx.getFunc(id).toIR(args.flatMap(_.getType(ctx)))
    }
  }
  final case class ParenExpr(expr: Expr) extends Expr {
    override def _getType(ctx: Compiler.Ctx): Seq[Val.Type] = expr.getType(ctx: Compiler.Ctx)

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = expr.toIR(ctx)
  }

  sealed trait Statement {
    def check(ctx: Compiler.Ctx): Unit
    def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]]
  }
  object Statement {
    @inline def getCondIR(condition: Expr,
                          ctx: Compiler.Ctx,
                          offset: Byte): Seq[Instr[StatelessContext]] = {
      condition match {
        case Binop(op: TestOperator, left, right) =>
          left.toIR(ctx) ++ right.toIR(ctx) ++ op.toBranchIR(left.getType(ctx), offset)
        case UnaryOp(op: LogicalOperator, expr) =>
          expr.toIR(ctx) ++ op.toBranchIR(expr.getType(ctx), offset)
        case _ =>
          condition.toIR(ctx) :+ IfFalse(offset)
      }
    }
  }
  final case class VarDef(isMutable: Boolean, ident: Ident, value: Expr) extends Statement {
    override def check(ctx: Compiler.Ctx): Unit =
      ctx.addVariable(ident, value.getType(ctx: Compiler.Ctx), isMutable)

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      value.toIR(ctx) :+ ctx.toIR(ident)
    }
  }
  final case class FuncDef(ident: Ident,
                           args: Seq[Argument],
                           rtypes: Seq[Val.Type],
                           body: Seq[Statement]) {
    def check(ctx: Compiler.Ctx): Unit = {
      ctx.setFuncScope(ident)
      args.foreach(arg => ctx.addVariable(arg.ident, arg.tpe, arg.isMutable))
      body.foreach(_.check(ctx))
    }

    def toMethod(ctx: Compiler.Ctx): Method[StatelessContext] = {
      ctx.setFuncScope(ident)
      val localVars  = ctx.getLocalVars(ident)
      val localsType = localVars.map(_.tpe)
      val instrs     = body.flatMap(_.toIR(ctx))
      Method[StatelessContext](AVector.from(localsType), AVector.from(rtypes), AVector.from(instrs))
    }
  }
  final case class Assign(target: Ident, rhs: Expr) extends Statement {
    override def check(ctx: Compiler.Ctx): Unit = {
      ctx.checkAssign(target, rhs.getType(ctx: Compiler.Ctx))
    }

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      rhs.toIR(ctx) :+ ctx.toIR(target)
    }
  }
  final case class FuncCall(id: CallId, args: Seq[Expr]) extends Statement {
    override def check(ctx: Compiler.Ctx): Unit = {
      val funcInfo = ctx.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(ctx)))
      ()
    }

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      val func       = ctx.getFunc(id)
      val argsType   = args.flatMap(_.getType(ctx))
      val returnType = func.getReturnType(argsType)
      args.flatMap(_.toIR(ctx)) ++ func.toIR(argsType) ++ Seq.fill(returnType.length)(Pop)
    }
  }
  final case class IfElse(condition: Expr, ifBranch: Seq[Statement], elseBranch: Seq[Statement])
      extends Statement {
    override def check(ctx: Compiler.Ctx): Unit = {
      if (condition.getType(ctx) != Seq(Val.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr $condition")
      } else {
        ifBranch.foreach(_.check(ctx))
        elseBranch.foreach(_.check(ctx))
      }
    }

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      val elseIRs  = elseBranch.flatMap(_.toIR(ctx))
      val offsetIR = if (elseIRs.nonEmpty) Seq(Forward(elseIRs.length.toByte)) else Seq.empty
      val ifIRs    = ifBranch.flatMap(_.toIR(ctx)) ++ offsetIR
      if (ifIRs.length > 0xFF || elseIRs.length > 0xFF) {
        // TODO: support long branches
        throw Compiler.Error(s"Too many instrs for if-else branches")
      }
      val condIR = Statement.getCondIR(condition, ctx, ifIRs.length.toByte)
      condIR ++ ifIRs ++ elseIRs
    }
  }
  final case class While(condition: Expr, body: Seq[Statement]) extends Statement {
    override def check(ctx: Compiler.Ctx): Unit = {
      if (condition.getType(ctx) != Seq(Val.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr $condition")
      } else {
        body.foreach(_.check(ctx))
      }
    }

    override def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      val bodyIR   = body.flatMap(_.toIR(ctx))
      val condIR   = Statement.getCondIR(condition, ctx, (bodyIR.length + 1).toByte)
      val whileLen = condIR.length + bodyIR.length + 1
      if (whileLen > 0xFF) {
        // TODO: support long branches
        throw Compiler.Error(s"Too many instrs for if-else branches")
      }
      condIR ++ bodyIR :+ Backward(whileLen.toByte)
    }
  }
  final case class ReturnStmt(exprs: Seq[Expr]) extends Statement {
    override def check(ctx: Compiler.Ctx): Unit = {
      ctx.checkReturn(exprs.flatMap(_.getType(ctx)))
    }
    def toIR(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] =
      exprs.flatMap(_.toIR(ctx)) ++ (if (exprs.isEmpty) Seq() else Seq(Return))
  }

  final case class Contract(ident: Ident, fields: Seq[Argument], funcs: Seq[FuncDef]) {
    def check(): Compiler.Ctx = {
      val ctx = Compiler.Ctx.empty
      fields.foreach(field => ctx.addVariable(field.ident, field.tpe, field.isMutable))
      ctx.addFuncDefs(funcs)
      funcs.foreach(_.check(ctx))
      ctx
    }

    def toIR(ctx: Compiler.Ctx): StatelessScript = {
      val fieldsTypes = AVector.from(fields.map(assign => ctx.getType(assign.ident)))
      val methods     = AVector.from(funcs.map(func    => func.toMethod(ctx)))
      StatelessScript(fieldsTypes, methods)
    }
  }
}
