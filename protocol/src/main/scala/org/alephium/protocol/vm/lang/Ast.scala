package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.{Contract => VmContract, _}
import org.alephium.util.AVector

object Ast {
  final case class Ident(name: String)
  final case class TypeId(name: String)
  final case class FuncId(name: String, isBuiltIn: Boolean)
  final case class Argument(ident: Ident, tpe: Type, isMutable: Boolean)

  object FuncId {
    def empty: FuncId = FuncId("", isBuiltIn = false)
  }

  sealed trait Expr[Ctx <: StatelessContext] {
    var tpe: Option[Seq[Type]] = None
    protected def _getType(state: Compiler.State[Ctx]): Seq[Type]
    def getType(state: Compiler.State[Ctx]): Seq[Type] = tpe match {
      case Some(ts) => ts
      case None =>
        val t = _getType(state)
        tpe = Some(t)
        t
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  final case class Const[Ctx <: StatelessContext](v: Val) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(Type.fromVal(v.tpe))

    // TODO: support constants for all values
    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
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
  final case class Variable[Ctx <: StatelessContext](id: Ident) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(state.getType(id))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val varInfo = state.getVariable(id)
      if (state.isField(id)) Seq(LoadField(varInfo.index))
      else Seq(LoadLocal(varInfo.index))
    }
  }
  final case class UnaryOp[Ctx <: StatelessContext](op: Operator, expr: Expr[Ctx])
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      op.getReturnType(expr.getType(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      expr.genCode(state) ++ op.genCode(expr.getType(state))
    }
  }
  final case class Binop[Ctx <: StatelessContext](op: Operator, left: Expr[Ctx], right: Expr[Ctx])
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      op.getReturnType(left.getType(state) ++ right.getType(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      left.genCode(state) ++ right.genCode(state) ++ op.genCode(
        left.getType(state) ++ right.getType(state))
    }
  }
  final case class ContractConv[Ctx <: StatelessContext](contractType: TypeId, address: Expr[Ctx])
      extends Expr[Ctx] {
    override protected def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      if (address.getType(state) != Seq(Type.Byte32)) {
        throw Compiler.Error(s"Invalid expr $address for contract address")
      } else Seq(Type.Contract.stack(contractType))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      address.genCode(state)
  }
  final case class CallExpr[Ctx <: StatelessContext](id: FuncId, args: Seq[Expr[Ctx]])
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      val funcInfo = state.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      args.flatMap(_.genCode(state)) ++ state.getFunc(id).genCode(args.flatMap(_.getType(state)))
    }
  }
  final case class ContractCallExpr(objId: Ident, callId: FuncId, args: Seq[Expr[StatefulContext]])
      extends Expr[StatefulContext] {
    override protected def _getType(state: Compiler.State[StatefulContext]): Seq[Type] = {
      val funcInfo = state.getFunc(objId, callId)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
    }

    override def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      args.flatMap(_.genCode(state)) ++ Seq(LoadLocal(state.getVariable(objId).index)) ++
        state.getFunc(objId, callId).genCode(objId)
    }
  }
  final case class ParenExpr[Ctx <: StatelessContext](expr: Expr[Ctx]) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] =
      expr.getType(state: Compiler.State[Ctx])

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      expr.genCode(state)
  }

  sealed trait Statement[Ctx <: StatelessContext] {
    def check(state: Compiler.State[Ctx]): Unit
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  object Statement {
    @inline def getCondIR[Ctx <: StatelessContext](condition: Expr[Ctx],
                                                   state: Compiler.State[Ctx],
                                                   offset: Byte): Seq[Instr[Ctx]] = {
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
  final case class VarDef[Ctx <: StatelessContext](isMutable: Boolean,
                                                   ident: Ident,
                                                   value: Expr[Ctx])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit =
      state.addVariable(ident, value.getType(state), isMutable)

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      value.genCode(state) :+ state.genCode(ident)
    }
  }
  final case class FuncDef[Ctx <: StatelessContext](id: FuncId,
                                                    args: Seq[Argument],
                                                    rtypes: Seq[Type],
                                                    body: Seq[Statement[Ctx]]) {
    def check(state: Compiler.State[Ctx]): Unit = {
      args.foreach(arg => state.addVariable(arg.ident, arg.tpe, arg.isMutable))
      body.foreach(_.check(state))
    }

    def toMethod(state: Compiler.State[Ctx]): Method[Ctx] = {
      state.setFuncScope(id)
      check(state)

      val localVars  = state.getLocalVars(id)
      val localsType = localVars.map(_.tpe.toVal)
      val returnType = AVector.from(rtypes.view.map(_.toVal))
      val instrs     = body.flatMap(_.genCode(state))
      Method[Ctx](AVector.from(localsType), returnType, AVector.from(instrs))
    }
  }
  // TODO: handle multiple returns
  final case class Assign[Ctx <: StatelessContext](target: Ident, rhs: Expr[Ctx])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      state.checkAssign(target, rhs.getType(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      rhs.genCode(state) :+ state.genCode(target)
    }
  }
  final case class FuncCall[Ctx <: StatelessContext](id: FuncId, args: Seq[Expr[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      val funcInfo = state.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
      ()
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val func       = state.getFunc(id)
      val argsType   = args.flatMap(_.getType(state))
      val returnType = func.getReturnType(argsType)
      args.flatMap(_.genCode(state)) ++ func.genCode(argsType) ++ Seq.fill(returnType.length)(Pop)
    }
  }
  final case class ContractCall(objId: Ident, callId: FuncId, args: Seq[Expr[StatefulContext]])
      extends Statement[StatefulContext] {
    override def check(state: Compiler.State[StatefulContext]): Unit = {
      val funcInfo = state.getFunc(objId, callId)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
      ()
    }

    override def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      val func       = state.getFunc(objId, callId)
      val argsType   = args.flatMap(_.getType(state))
      val returnType = func.getReturnType(argsType)
      args.flatMap(_.genCode(state)) ++ Seq(LoadLocal(state.getVariable(objId).index)) ++
        func.genCode(objId) ++ Seq.fill[Instr[StatefulContext]](returnType.length)(Pop)
    }
  }
  final case class IfElse[Ctx <: StatelessContext](condition: Expr[Ctx],
                                                   ifBranch: Seq[Statement[Ctx]],
                                                   elseBranch: Seq[Statement[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr $condition")
      } else {
        ifBranch.foreach(_.check(state))
        elseBranch.foreach(_.check(state))
      }
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
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
  final case class While[Ctx <: StatelessContext](condition: Expr[Ctx], body: Seq[Statement[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr $condition")
      } else {
        body.foreach(_.check(state))
      }
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
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
  final case class ReturnStmt[Ctx <: StatelessContext](exprs: Seq[Expr[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      state.checkReturn(exprs.flatMap(_.getType(state)))
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      exprs.flatMap(_.genCode(state)) ++ (if (exprs.isEmpty) Seq() else Seq(Return))
  }

  trait Contract[Ctx <: StatelessContext] {
    def ident: TypeId
    def fields: Seq[Argument]
    def funcs: Seq[FuncDef[Ctx]]

    lazy val funcTable: Map[FuncId, Compiler.SimpleFunc[Ctx]] = {
      val table = Compiler.SimpleFunc.from(funcs).map(f => f.id -> f).toMap
      if (table.size != funcs.size) {
        val duplicates = funcs.groupBy(_.id).filter(_._2.size > 1).keys
        throw Compiler.Error(s"These functions ${duplicates} are defined multiple times")
      }
      table
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      fields.foreach(field => state.addVariable(field.ident, field.tpe, field.isMutable))
    }

    def genCode(state: Compiler.State[Ctx]): VmContract[Ctx]
  }

  final case class AssetScript(ident: TypeId, funcs: Seq[FuncDef[StatelessContext]])
      extends Contract[StatelessContext] {
    val fields: Seq[Argument] = Seq.empty

    def genCode(state: Compiler.State[StatelessContext]): StatelessScript = {
      check(state)
      val methods = AVector.from(funcs.view.map(func => func.toMethod(state)))
      StatelessScript(methods)
    }
  }

  sealed trait ContractWithState extends Contract[StatefulContext]

  final case class TxScript(ident: TypeId, funcs: Seq[FuncDef[StatefulContext]])
      extends ContractWithState {
    val fields: Seq[Argument] = Seq.empty

    def genCode(state: Compiler.State[StatefulContext]): StatefulScript = {
      check(state)
      val methods = AVector.from(funcs.view.map(func => func.toMethod(state)))
      StatefulScript(methods)
    }
  }

  final case class TxContract(ident: TypeId,
                              fields: Seq[Argument],
                              funcs: Seq[FuncDef[StatefulContext]])
      extends ContractWithState {
    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      check(state)
      val fieldsTypes = AVector.from(fields.view.map(assign => assign.tpe.toVal))
      val methods     = AVector.from(funcs.view.map(func    => func.toMethod(state)))
      StatefulContract(fieldsTypes, methods)
    }
  }

  final case class MultiTxContract(contracts: Seq[ContractWithState]) {
    def get(contractIndex: Int): ContractWithState = {
      if (contractIndex >= 0 && contractIndex < contracts.size) contracts(contractIndex)
      else throw Compiler.Error(s"Invalid contract index $contractIndex")
    }

    def genStatefulScript(contractIndex: Int): StatefulScript = {
      val state = Compiler.State.buildFor(this, contractIndex)
      get(contractIndex) match {
        case script: TxScript => script.genCode(state)
        case _: TxContract    => throw Compiler.Error(s"The code is for TxContract, not for TxScript")
      }
    }

    def genStatefulContract(contractIndex: Int): StatefulContract = {
      val state = Compiler.State.buildFor(this, contractIndex)
      get(contractIndex) match {
        case contract: TxContract => contract.genCode(state)
        case _: TxScript          => throw Compiler.Error(s"The code is for TxScript, not for TxContract")
      }
    }
  }
}
