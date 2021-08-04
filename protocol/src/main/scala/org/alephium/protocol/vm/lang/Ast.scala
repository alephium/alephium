// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.{Contract => VmContract, _}
import org.alephium.util.AVector

// scalastyle:off number.of.methods
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
    def getType(state: Compiler.State[Ctx]): Seq[Type] =
      tpe match {
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
        case Val.Bool(b)    => Seq(if (b) ConstTrue else ConstFalse)
        case v: Val.I256    => Seq(ConstInstr.i256(v))
        case v: Val.U256    => Seq(ConstInstr.u256(v))
        case v: Val.ByteVec => Seq(BytesConst(v))
        case v: Val.Address => Seq(AddressConst(v))
      }
    }
  }
  final case class Variable[Ctx <: StatelessContext](id: Ident) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(state.getType(id))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      Seq(state.genLoadCode(id))
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
        left.getType(state) ++ right.getType(state)
      )
    }
  }
  final case class ContractConv[Ctx <: StatelessContext](contractType: TypeId, address: Expr[Ctx])
      extends Expr[Ctx] {
    override protected def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      if (address.getType(state) != Seq(Type.ByteVec)) {
        throw Compiler.Error(s"Invalid expr $address for contract address")
      } else {
        Seq(Type.Contract.stack(contractType))
      }
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

  trait ContractCallBase {
    def obj: Expr[StatefulContext]
    def callId: FuncId
    def args: Seq[Expr[StatefulContext]]

    def _getTypeBase(state: Compiler.State[StatefulContext]): Seq[Type] = {
      val objType = obj.getType(state)
      if (objType.length != 1) {
        throw Compiler.Error(s"Expect single type from $obj")
      } else {
        objType(0) match {
          case contract: Type.Contract =>
            val funcInfo = state.getFunc(contract.id, callId)
            funcInfo.getReturnType(args.flatMap(_.getType(state)))
          case _ =>
            throw Compiler.Error(s"Expect contract for $callId of $obj")
        }
      }
    }
  }
  final case class ContractCallExpr(
      obj: Expr[StatefulContext],
      callId: FuncId,
      args: Seq[Expr[StatefulContext]]
  ) extends Expr[StatefulContext]
      with ContractCallBase {
    override def _getType(state: Compiler.State[StatefulContext]): Seq[Type] =
      _getTypeBase(state)

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    override def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      val contract = obj.getType(state)(0).asInstanceOf[Type.Contract]
      args.flatMap(_.genCode(state)) ++ obj.genCode(state) ++
        state.getFunc(contract.id, callId).genExternalCallCode(contract.id)
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
    @inline def getCondIR[Ctx <: StatelessContext](
        condition: Expr[Ctx],
        state: Compiler.State[Ctx],
        offset: Int
    ): Seq[Instr[Ctx]] = {
      condition match {
        case UnaryOp(Not, expr) =>
          expr.genCode(state) :+ IfTrue(offset)
        case _ =>
          condition.genCode(state) :+ IfFalse(offset)
      }
    }
  }
  final case class VarDef[Ctx <: StatelessContext](
      isMutable: Boolean,
      ident: Ident,
      value: Expr[Ctx]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit =
      state.addVariable(ident, value.getType(state), isMutable)

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      value.genCode(state) :+ state.genStoreCode(ident)
    }
  }
  final case class FuncDef[Ctx <: StatelessContext](
      id: FuncId,
      isPublic: Boolean,
      isPayable: Boolean,
      args: Seq[Argument],
      rtypes: Seq[Type],
      body: Seq[Statement[Ctx]]
  ) {
    def check(state: Compiler.State[Ctx]): Unit = {
      args.foreach(arg => state.addVariable(arg.ident, arg.tpe, arg.isMutable))
      body.foreach(_.check(state))
    }

    def toMethod(state: Compiler.State[Ctx]): Method[Ctx] = {
      state.setFuncScope(id)
      check(state)

      val localVars = state.getLocalVars(id)
      val instrs    = body.flatMap(_.genCode(state))
      Method[Ctx](
        isPublic,
        isPayable,
        argsLength = args.length,
        localsLength = localVars.length,
        returnLength = rtypes.length,
        AVector.from(instrs)
      )
    }
  }
  // TODO: handle multiple returns
  final case class Assign[Ctx <: StatelessContext](target: Ident, rhs: Expr[Ctx])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      state.checkAssign(target, rhs.getType(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      rhs.genCode(state) :+ state.genStoreCode(target)
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
  final case class ContractCall(
      obj: Expr[StatefulContext],
      callId: FuncId,
      args: Seq[Expr[StatefulContext]]
  ) extends Statement[StatefulContext]
      with ContractCallBase {
    override def check(state: Compiler.State[StatefulContext]): Unit = {
      _getTypeBase(state)
      ()
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    override def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      val contract   = obj.getType(state)(0).asInstanceOf[Type.Contract]
      val func       = state.getFunc(contract.id, callId)
      val argsType   = args.flatMap(_.getType(state))
      val returnType = func.getReturnType(argsType)
      args.flatMap(_.genCode(state)) ++ obj.genCode(state) ++
        func.genExternalCallCode(contract.id) ++
        Seq.fill[Instr[StatefulContext]](returnType.length)(Pop)
    }
  }
  final case class IfElse[Ctx <: StatelessContext](
      condition: Expr[Ctx],
      ifBranch: Seq[Statement[Ctx]],
      elseBranch: Seq[Statement[Ctx]]
  ) extends Statement[Ctx] {
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
      val offsetIR = if (elseIRs.nonEmpty) Seq(Jump(elseIRs.length)) else Seq.empty
      val ifIRs    = ifBranch.flatMap(_.genCode(state)) ++ offsetIR
      if (ifIRs.length > 0xff || elseIRs.length > 0xff) {
        // TODO: support long branches
        throw Compiler.Error(s"Too many instrs for if-else branches")
      }
      val condIR = Statement.getCondIR(condition, state, ifIRs.length)
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
      val condIR   = Statement.getCondIR(condition, state, bodyIR.length + 1)
      val whileLen = condIR.length + bodyIR.length + 1
      if (whileLen > 0xff) {
        // TODO: support long branches
        throw Compiler.Error(s"Too many instrs for if-else branches")
      }
      condIR ++ bodyIR :+ Jump(-whileLen)
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
      StatefulScript
        .from(methods)
        .getOrElse(
          throw Compiler.Error(
            "Expect the 1st function to be public and the other functions to be private for tx script"
          )
        )
    }
  }

  final case class TxContract(
      ident: TypeId,
      fields: Seq[Argument],
      funcs: Seq[FuncDef[StatefulContext]]
  ) extends ContractWithState {
    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      check(state)
      val methods = AVector.from(funcs.view.map(func => func.toMethod(state)))
      StatefulContract(fields.length, methods)
    }
  }

  final case class MultiTxContract(contracts: Seq[ContractWithState]) {
    def get(contractIndex: Int): ContractWithState = {
      if (contractIndex >= 0 && contractIndex < contracts.size) {
        contracts(contractIndex)
      } else {
        throw Compiler.Error(s"Invalid contract index $contractIndex")
      }
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
