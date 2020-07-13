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
    protected def _getType(state: Compiler.State): Seq[Type]
    def getType(state: Compiler.State): Seq[Type] = tpe match {
      case Some(ts) => ts
      case None =>
        val t = _getType(state)
        tpe = Some(t)
        t
    }
    def genCode(state: Compiler.State): Seq[Instr[StatelessContext]]
  }
  final case class Const(v: Val) extends Expr {
    override def _getType(state: Compiler.State): Seq[Type] = Seq(Type.fromVal(v.tpe))

    // TODO: support constants for all values
    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
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
    override def _getType(state: Compiler.State): Seq[Type] = Seq(state.getType(id))

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      val varInfo = state.getVariable(id)
      if (state.isField(id)) Seq(LoadField(varInfo.index))
      else Seq(LoadLocal(varInfo.index))
    }
  }
  final case class UnaryOp(op: Operator, expr: Expr) extends Expr {
    override def _getType(state: Compiler.State): Seq[Type] = {
      op.getReturnType(expr.getType(state))
    }

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      expr.genCode(state) ++ op.genCode(expr.getType(state))
    }
  }
  final case class Binop(op: Operator, left: Expr, right: Expr) extends Expr {
    override def _getType(state: Compiler.State): Seq[Type] = {
      op.getReturnType(left.getType(state) ++ right.getType(state))
    }

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      left.genCode(state) ++ right.genCode(state) ++ op.genCode(
        left.getType(state) ++ right.getType(state))
    }
  }
  final case class ContractConv(contractType: TypeId, address: Expr) extends Expr {
    override protected def _getType(state: Compiler.State): Seq[Type] = {
      if (address.getType(state) != Seq(Type.Byte32)) {
        throw Compiler.Error(s"Invalid expr $address for contract address")
      } else Seq(Type.Contract.stack(contractType))
    }

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] =
      address.genCode(state)
  }
  final case class CallExpr(id: FuncId, args: Seq[Expr]) extends Expr {
    override def _getType(state: Compiler.State): Seq[Type] = {
      val funcInfo = state.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
    }

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      args.flatMap(_.genCode(state)) ++ state.getFunc(id).genCode(args.flatMap(_.getType(state)))
    }
  }
  final case class ContractCallExpr(objId: Ident, callId: FuncId, args: Seq[Expr]) extends Expr {
    override protected def _getType(state: Compiler.State): Seq[Type] = {
      val funcInfo = state.getFunc(objId, callId)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
    }

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      args.flatMap(_.genCode(state)) ++ Seq(LoadLocal(state.getVariable(objId).index)) ++
        state.getFunc(objId, callId).genCode(objId)
    }
  }
  final case class ParenExpr(expr: Expr) extends Expr {
    override def _getType(state: Compiler.State): Seq[Type] = expr.getType(state: Compiler.State)

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = expr.genCode(state)
  }

  sealed trait Statement {
    def check(state: Compiler.State): Unit
    def genCode(state: Compiler.State): Seq[Instr[StatelessContext]]
  }
  object Statement {
    @inline def getCondIR(condition: Expr,
                          state: Compiler.State,
                          offset: Byte): Seq[Instr[StatelessContext]] = {
      condition match {
        case Binop(op: TestOperator, left, right) =>
          left.genCode(state) ++ right.genCode(state) ++ op.toBranchIR(left.getType(state), offset)
        case UnaryOp(op: LogicalOperator, expr) =>
          expr.genCode(state) ++ op.toBranchIR(expr.getType(state), offset)
        case _ =>
          condition.genCode(state) :+ IfFalse(offset)
      }
    }
  }
  final case class VarDef(isMutable: Boolean, ident: Ident, value: Expr) extends Statement {
    override def check(state: Compiler.State): Unit =
      state.addVariable(ident, value.getType(state: Compiler.State), isMutable)

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      value.genCode(state) :+ state.genCode(ident)
    }
  }
  final case class FuncDef(id: FuncId,
                           args: Seq[Argument],
                           rtypes: Seq[Type],
                           body: Seq[Statement]) {
    def check(state: Compiler.State): Unit = {
      args.foreach(arg => state.addVariable(arg.ident, arg.tpe, arg.isMutable))
      body.foreach(_.check(state))
    }

    def toMethod(state: Compiler.State): Method[StatelessContext] = {
      state.setFuncScope(id)
      check(state)

      val localVars  = state.getLocalVars(id)
      val localsType = localVars.map(_.tpe.toVal)
      val returnType = AVector.from(rtypes.view.map(_.toVal))
      val instrs     = body.flatMap(_.genCode(state))
      Method[StatelessContext](AVector.from(localsType), returnType, AVector.from(instrs))
    }
  }
  // TODO: handle multiple returns
  final case class Assign(target: Ident, rhs: Expr) extends Statement {
    override def check(state: Compiler.State): Unit = {
      state.checkAssign(target, rhs.getType(state: Compiler.State))
    }

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      rhs.genCode(state) :+ state.genCode(target)
    }
  }
  final case class FuncCall(id: FuncId, args: Seq[Expr]) extends Statement {
    override def check(state: Compiler.State): Unit = {
      val funcInfo = state.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
      ()
    }

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      val func       = state.getFunc(id)
      val argsType   = args.flatMap(_.getType(state))
      val returnType = func.getReturnType(argsType)
      args.flatMap(_.genCode(state)) ++ func.genCode(argsType) ++ Seq.fill(returnType.length)(Pop)
    }
  }
  final case class ContractCall(objId: Ident, callId: FuncId, args: Seq[Expr]) extends Statement {
    override def check(state: Compiler.State): Unit = {
      val funcInfo = state.getFunc(objId, callId)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
      ()
    }

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      val func       = state.getFunc(objId, callId)
      val argsType   = args.flatMap(_.getType(state))
      val returnType = func.getReturnType(argsType)
      args.flatMap(_.genCode(state)) ++ Seq(LoadLocal(state.getVariable(objId).index)) ++
        func.genCode(objId) ++ Seq.fill[Instr[StatelessContext]](returnType.length)(Pop)
    }
  }
  final case class IfElse(condition: Expr, ifBranch: Seq[Statement], elseBranch: Seq[Statement])
      extends Statement {
    override def check(state: Compiler.State): Unit = {
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr $condition")
      } else {
        ifBranch.foreach(_.check(state))
        elseBranch.foreach(_.check(state))
      }
    }

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      val elseIRs  = elseBranch.flatMap(_.genCode(state))
      val offsetIR = if (elseIRs.nonEmpty) Seq(Forward(elseIRs.length.toByte)) else Seq.empty
      val ifIRs    = ifBranch.flatMap(_.genCode(state)) ++ offsetIR
      if (ifIRs.length > 0xFF || elseIRs.length > 0xFF) {
        // TODO: support long branches
        throw Compiler.Error(s"Too many instrs for if-else branches")
      }
      val condIR = Statement.getCondIR(condition, state, ifIRs.length.toByte)
      condIR ++ ifIRs ++ elseIRs
    }
  }
  final case class While(condition: Expr, body: Seq[Statement]) extends Statement {
    override def check(state: Compiler.State): Unit = {
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr $condition")
      } else {
        body.foreach(_.check(state))
      }
    }

    override def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] = {
      val bodyIR   = body.flatMap(_.genCode(state))
      val condIR   = Statement.getCondIR(condition, state, (bodyIR.length + 1).toByte)
      val whileLen = condIR.length + bodyIR.length + 1
      if (whileLen > 0xFF) {
        // TODO: support long branches
        throw Compiler.Error(s"Too many instrs for if-else branches")
      }
      condIR ++ bodyIR :+ Backward(whileLen.toByte)
    }
  }
  final case class ReturnStmt(exprs: Seq[Expr]) extends Statement {
    override def check(state: Compiler.State): Unit = {
      state.checkReturn(exprs.flatMap(_.getType(state)))
    }
    def genCode(state: Compiler.State): Seq[Instr[StatelessContext]] =
      exprs.flatMap(_.genCode(state)) ++ (if (exprs.isEmpty) Seq() else Seq(Return))
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

    def check(state: Compiler.State): Unit = {
      fields.foreach(field => state.addVariable(field.ident, field.tpe, field.isMutable))
    }

    def genCode(state: Compiler.State): StatelessScript = {
      check(state)
      val fieldsTypes = AVector.from(fields.view.map(assign => assign.tpe.toVal))
      val methods     = AVector.from(funcs.view.map(func    => func.toMethod(state)))
      StatelessScript(fieldsTypes, methods)
    }
  }

  final case class MultiContract(contracts: Seq[Contract]) {
    def get(contractIndex: Int): Contract = {
      if (contractIndex >= 0 && contractIndex < contracts.size) contracts(contractIndex)
      else throw Compiler.Error(s"Invalid contract index $contractIndex")
    }

    def genCode(state: Compiler.State, contractIndex: Int): StatelessScript = {
      get(contractIndex).genCode(state)
    }
  }
}
