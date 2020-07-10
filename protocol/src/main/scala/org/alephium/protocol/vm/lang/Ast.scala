package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm._
import org.alephium.util.AVector

object Ast {
  final case class Ident(name: String)
  final case class TypeId(name: String)
  final case class FuncId(name: String, isBuiltIn: Boolean)
  final case class Argument(ident: Ident, tpe: Type, isMutable: Boolean)

  object FuncId {
    def empty: FuncId = FuncId("", isBuiltIn = false)
  }

  sealed trait Expr {
    var tpe: Option[Seq[Type]] = None
    protected def _getType(ctx: Compiler.Ctx): Seq[Type]
    def getType(ctx: Compiler.Ctx): Seq[Type] = tpe match {
      case Some(ts) => ts
      case None =>
        val t = _getType(ctx)
        tpe = Some(t)
        t
    }
    def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]]
  }
  final case class Const(v: Val) extends Expr {
    override def _getType(ctx: Compiler.Ctx): Seq[Type] = Seq(Type.fromVal(v.tpe))

    // TODO: support constants for all values
    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      v match {
        case Val.Bool(b)      => Seq(if (b) BoolConstTrue else BoolConstFalse)
        case _: Val.Byte      => ???
        case v: Val.I64       => Seq(ConstInstr.i64(v))
        case v: Val.U64       => Seq(ConstInstr.u64(v))
        case v: Val.I256      => Seq(ConstInstr.i256(v))
        case v: Val.U256      => Seq(ConstInstr.u256(v))
        case v: Val.Byte32    => Seq(Byte32Const(v))
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
    override def _getType(ctx: Compiler.Ctx): Seq[Type] = Seq(ctx.getType(id))

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      val varInfo = ctx.getVariable(id)
      if (ctx.isField(id)) Seq(LoadField(varInfo.index))
      else Seq(LoadLocal(varInfo.index))
    }
  }
  final case class UnaryOp(op: Operator, expr: Expr) extends Expr {
    override def _getType(ctx: Compiler.Ctx): Seq[Type] = {
      op.getReturnType(expr.getType(ctx))
    }

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      expr.genCode(ctx) ++ op.genCode(expr.getType(ctx))
    }
  }
  final case class Binop(op: Operator, left: Expr, right: Expr) extends Expr {
    override def _getType(ctx: Compiler.Ctx): Seq[Type] = {
      op.getReturnType(left.getType(ctx) ++ right.getType(ctx))
    }

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      left.genCode(ctx) ++ right.genCode(ctx) ++ op.genCode(left.getType(ctx) ++ right.getType(ctx))
    }
  }
  final case class ContractConv(contractType: TypeId, address: Expr) extends Expr {
    override protected def _getType(ctx: Compiler.Ctx): Seq[Type] = {
      if (address.getType(ctx) != Seq(Type.Byte32)) {
        throw Compiler.Error(s"Invalid expr $address for contract address")
      } else Seq(Type.Contract.stack(contractType))
    }

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = address.genCode(ctx)
  }
  final case class CallExpr(id: FuncId, args: Seq[Expr]) extends Expr {
    override def _getType(ctx: Compiler.Ctx): Seq[Type] = {
      val funcInfo = ctx.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(ctx)))
    }

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      args.flatMap(_.genCode(ctx)) ++ ctx.getFunc(id).genCode(args.flatMap(_.getType(ctx)))
    }
  }
  final case class ContractCallExpr(objId: Ident, callId: FuncId, args: Seq[Expr]) extends Expr {
    override protected def _getType(ctx: Compiler.Ctx): Seq[Type] = {
      val funcInfo = ctx.getFunc(objId, callId)
      funcInfo.getReturnType(args.flatMap(_.getType(ctx)))
    }

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      args.flatMap(_.genCode(ctx)) ++ Seq(LoadLocal(ctx.getVariable(objId).index)) ++
        ctx.getFunc(objId, callId).genCode(objId)
    }
  }
  final case class ParenExpr(expr: Expr) extends Expr {
    override def _getType(ctx: Compiler.Ctx): Seq[Type] = expr.getType(ctx: Compiler.Ctx)

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = expr.genCode(ctx)
  }

  sealed trait Statement {
    def check(ctx: Compiler.Ctx): Unit
    def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]]
  }
  object Statement {
    @inline def getCondIR(condition: Expr,
                          ctx: Compiler.Ctx,
                          offset: Byte): Seq[Instr[StatelessContext]] = {
      condition match {
        case Binop(op: TestOperator, left, right) =>
          left.genCode(ctx) ++ right.genCode(ctx) ++ op.toBranchIR(left.getType(ctx), offset)
        case UnaryOp(op: LogicalOperator, expr) =>
          expr.genCode(ctx) ++ op.toBranchIR(expr.getType(ctx), offset)
        case _ =>
          condition.genCode(ctx) :+ IfFalse(offset)
      }
    }
  }
  final case class VarDef(isMutable: Boolean, ident: Ident, value: Expr) extends Statement {
    override def check(ctx: Compiler.Ctx): Unit =
      ctx.addVariable(ident, value.getType(ctx: Compiler.Ctx), isMutable)

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      value.genCode(ctx) :+ ctx.genCode(ident)
    }
  }
  final case class FuncDef(id: FuncId,
                           args: Seq[Argument],
                           rtypes: Seq[Type],
                           body: Seq[Statement]) {
    def check(ctx: Compiler.Ctx): Unit = {
      args.foreach(arg => ctx.addVariable(arg.ident, arg.tpe, arg.isMutable))
      body.foreach(_.check(ctx))
    }

    def toMethod(ctx: Compiler.Ctx): Method[StatelessContext] = {
      ctx.setFuncScope(id)
      check(ctx)

      val localVars  = ctx.getLocalVars(id)
      val localsType = localVars.map(_.tpe.toVal)
      val returnType = AVector.from(rtypes.view.map(_.toVal))
      val instrs     = body.flatMap(_.genCode(ctx))
      Method[StatelessContext](AVector.from(localsType), returnType, AVector.from(instrs))
    }
  }
  // TODO: handle multiple returns
  final case class Assign(target: Ident, rhs: Expr) extends Statement {
    override def check(ctx: Compiler.Ctx): Unit = {
      ctx.checkAssign(target, rhs.getType(ctx: Compiler.Ctx))
    }

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      rhs.genCode(ctx) :+ ctx.genCode(target)
    }
  }
  final case class FuncCall(id: FuncId, args: Seq[Expr]) extends Statement {
    override def check(ctx: Compiler.Ctx): Unit = {
      val funcInfo = ctx.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(ctx)))
      ()
    }

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      val func       = ctx.getFunc(id)
      val argsType   = args.flatMap(_.getType(ctx))
      val returnType = func.getReturnType(argsType)
      args.flatMap(_.genCode(ctx)) ++ func.genCode(argsType) ++ Seq.fill(returnType.length)(Pop)
    }
  }
  final case class ContractCall(objId: Ident, callId: FuncId, args: Seq[Expr]) extends Statement {
    override def check(ctx: Compiler.Ctx): Unit = {
      val funcInfo = ctx.getFunc(objId, callId)
      funcInfo.getReturnType(args.flatMap(_.getType(ctx)))
      ()
    }

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      val func       = ctx.getFunc(objId, callId)
      val argsType   = args.flatMap(_.getType(ctx))
      val returnType = func.getReturnType(argsType)
      args.flatMap(_.genCode(ctx)) ++ Seq(LoadLocal(ctx.getVariable(objId).index)) ++
        func.genCode(objId) ++ Seq.fill[Instr[StatelessContext]](returnType.length)(Pop)
    }
  }
  final case class IfElse(condition: Expr, ifBranch: Seq[Statement], elseBranch: Seq[Statement])
      extends Statement {
    override def check(ctx: Compiler.Ctx): Unit = {
      if (condition.getType(ctx) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr $condition")
      } else {
        ifBranch.foreach(_.check(ctx))
        elseBranch.foreach(_.check(ctx))
      }
    }

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      val elseIRs  = elseBranch.flatMap(_.genCode(ctx))
      val offsetIR = if (elseIRs.nonEmpty) Seq(Forward(elseIRs.length.toByte)) else Seq.empty
      val ifIRs    = ifBranch.flatMap(_.genCode(ctx)) ++ offsetIR
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
      if (condition.getType(ctx) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr $condition")
      } else {
        body.foreach(_.check(ctx))
      }
    }

    override def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] = {
      val bodyIR   = body.flatMap(_.genCode(ctx))
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
    def genCode(ctx: Compiler.Ctx): Seq[Instr[StatelessContext]] =
      exprs.flatMap(_.genCode(ctx)) ++ (if (exprs.isEmpty) Seq() else Seq(Return))
  }

  final case class Contract(ident: TypeId, fields: Seq[Argument], funcs: Seq[FuncDef]) {
    lazy val funcTable: Map[FuncId, Compiler.SimpleFunc] = {
      val table = Compiler.SimpleFunc.from(funcs).map(f => f.id -> f).toMap
      if (table.size != funcs.size) {
        val duplicates = funcs.groupBy(_.id).filter(_._2.size > 1).keys
        throw Compiler.Error(s"These functions ${duplicates} are defined multiple times")
      }
      table
    }

    def check(ctx: Compiler.Ctx): Unit = {
      fields.foreach(field => ctx.addVariable(field.ident, field.tpe, field.isMutable))
    }

    def genCode(ctx: Compiler.Ctx): StatelessScript = {
      check(ctx)
      val fieldsTypes = AVector.from(fields.view.map(assign => assign.tpe.toVal))
      val methods     = AVector.from(funcs.view.map(func    => func.toMethod(ctx)))
      StatelessScript(fieldsTypes, methods)
    }
  }

  final case class MultiContract(contracts: Seq[Contract]) {
    def get(contractIndex: Int): Contract = {
      if (contractIndex >= 0 && contractIndex < contracts.size) contracts(contractIndex)
      else throw Compiler.Error(s"Invalid contract index $contractIndex")
    }

    def genCode(ctx: Compiler.Ctx, contractIndex: Int): StatelessScript = {
      get(contractIndex).genCode(ctx)
    }
  }
}
