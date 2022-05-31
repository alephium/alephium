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

import scala.collection.mutable

import org.alephium.protocol.config.CompilerConfig
import org.alephium.protocol.vm.{Contract => VmContract, _}
import org.alephium.protocol.vm.lang.LogicalOperator.Not
import org.alephium.util.{AVector, I256, U256}

// scalastyle:off number.of.methods number.of.types file.size.limit
object Ast {
  final case class Ident(name: String)
  final case class TypeId(name: String)
  final case class FuncId(name: String, isBuiltIn: Boolean)
  final case class Argument(ident: Ident, tpe: Type, isMutable: Boolean) {
    def signature: String = {
      val prefix = if (isMutable) "mut " else ""
      s"${prefix}${ident.name}:${tpe.signature}"
    }
  }

  final case class EventField(ident: Ident, tpe: Type) {
    def signature: String = s"${ident.name}:${tpe.signature}"
  }

  final case class AnnotationField(ident: Ident, value: Val)
  final case class Annotation(id: Ident, fields: Seq[AnnotationField])

  object FuncId {
    def empty: FuncId = FuncId("", isBuiltIn = false)
  }

  trait Typed[Ctx <: StatelessContext, T] {
    var tpe: Option[T] = None
    protected def _getType(state: Compiler.State[Ctx]): T
    def getType(state: Compiler.State[Ctx]): T =
      tpe match {
        case Some(ts) => ts
        case None =>
          val t = _getType(state)
          tpe = Some(t)
          t
      }
  }

  sealed trait Expr[Ctx <: StatelessContext] extends Typed[Ctx, Seq[Type]] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
    def fillPlaceholder(expr: Ast.Const[Ctx]): Expr[Ctx]
  }
  final case class Const[Ctx <: StatelessContext](v: Val) extends Expr[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Expr[Ctx] = this

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(Type.fromVal(v.tpe))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      Seq(v.toConstInstr)
    }
  }
  final case class CreateArrayExpr[Ctx <: StatelessContext](elements: Seq[Expr[Ctx]])
      extends Expr[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Expr[Ctx] = {
      val newElements = elements.map(_.fillPlaceholder(expr))
      if (newElements == elements) this else CreateArrayExpr(newElements)
    }

    override def _getType(state: Compiler.State[Ctx]): Seq[Type.FixedSizeArray] = {
      assume(elements.nonEmpty)
      val baseType = elements(0).getType(state)
      if (baseType.length != 1) {
        throw Compiler.Error("Expect single type for array element")
      }
      if (elements.drop(0).exists(_.getType(state) != baseType)) {
        throw Compiler.Error(s"Array elements should have same type")
      }
      Seq(Type.FixedSizeArray(baseType(0), elements.size))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      elements.flatMap(_.genCode(state))
    }
  }
  def getConstantArrayIndex[Ctx <: StatelessContext](index: Expr[Ctx]): Int = {
    index match {
      case Ast.Const(Val.U256(v)) =>
        v.toInt.getOrElse(throw Compiler.Error(s"Invalid array index $v"))
      case _: Ast.Placeholder[Ctx] => throw Compiler.Error("Placeholder only allowed in loop")
      case _                       => throw Compiler.Error(s"Invalid array index $index")
    }
  }
  final case class ArrayElement[Ctx <: StatelessContext](array: Expr[Ctx], index: Ast.Expr[Ctx])
      extends Expr[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Expr[Ctx] = {
      val newArray = array.fillPlaceholder(expr)
      val newIndex = index.fillPlaceholder(expr)
      if (newArray == array && newIndex == index) this else ArrayElement(newArray, newIndex)
    }

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      array.getType(state) match {
        case Seq(Type.FixedSizeArray(baseType, _)) => Seq(baseType)
        case tpe =>
          throw Compiler.Error(s"Expect array type, have: $tpe")
      }
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val idx               = getConstantArrayIndex(index)
      val (arrayRef, codes) = state.getOrCreateArrayRef(array, isMutable = false)
      if (arrayRef.isMultiDim()) {
        codes ++ arrayRef.subArray(idx).vars.flatMap(state.genLoadCode)
      } else {
        val ident = arrayRef.getVariable(idx)
        codes ++ state.genLoadCode(ident)
      }
    }
  }
  final case class Variable[Ctx <: StatelessContext](id: Ident) extends Expr[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Expr[Ctx] = this

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(state.getType(id))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.genLoadCode(id)
    }
  }
  final case class UnaryOp[Ctx <: StatelessContext](op: Operator, expr: Expr[Ctx])
      extends Expr[Ctx] {
    override def fillPlaceholder(const: Const[Ctx]): Expr[Ctx] = {
      val newExpr = expr.fillPlaceholder(const)
      if (newExpr == expr) this else UnaryOp(op, newExpr)
    }

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      op.getReturnType(expr.getType(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      expr.genCode(state) ++ op.genCode(expr.getType(state))
    }
  }
  final case class Binop[Ctx <: StatelessContext](op: Operator, left: Expr[Ctx], right: Expr[Ctx])
      extends Expr[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Expr[Ctx] = {
      val newLeft  = left.fillPlaceholder(expr)
      val newRight = right.fillPlaceholder(expr)
      if (newLeft == left && newRight == right) this else Binop(op, newLeft, newRight)
    }

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
    override def fillPlaceholder(expr: Const[Ctx]): Expr[Ctx] = {
      val newAddress = address.fillPlaceholder(expr)
      if (newAddress == address) this else ContractConv(contractType, newAddress)
    }

    override protected def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      state.checkContractType(contractType)
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
    override def fillPlaceholder(expr: Const[Ctx]): Expr[Ctx] = {
      val newArgs = args.map(_.fillPlaceholder(expr))
      if (newArgs == args) this else CallExpr(id, newArgs)
    }

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      val funcInfo = state.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val func = state.getFunc(id)
      args.flatMap(_.genCode(state)) ++
        (if (func.isVariadic) Seq(U256Const(Val.U256.unsafe(args.length))) else Seq.empty) ++
        func.genCode(args.flatMap(_.getType(state)))
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
    override def fillPlaceholder(expr: Const[StatefulContext]): Expr[StatefulContext] = {
      val newObj  = obj.fillPlaceholder(expr)
      val newArgs = args.map(_.fillPlaceholder(expr))
      if (newObj == obj && newArgs == args) this else ContractCallExpr(newObj, callId, newArgs)
    }

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
    override def fillPlaceholder(const: Const[Ctx]): Expr[Ctx] = {
      val newExpr = expr.fillPlaceholder(const)
      if (newExpr == expr) this else ParenExpr(newExpr)
    }

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] =
      expr.getType(state: Compiler.State[Ctx])

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      expr.genCode(state)
  }
  final case class Placeholder[Ctx <: StatelessContext]() extends Expr[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Expr[Ctx] = expr

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(Type.U256)

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      throw Compiler.Error("Placeholder only allowed in loop")
  }

  sealed trait Statement[Ctx <: StatelessContext] {
    def fillPlaceholder(expr: Ast.Const[Ctx]): Statement[Ctx]
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
      idents: Seq[(Boolean, Ident)],
      value: Expr[Ctx]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      val types = value.getType(state)
      if (types.length != idents.length) {
        throw Compiler.Error(
          s"Invalid variable def, expect ${types.length} vars, have ${idents.length} vars"
        )
      }
      idents.zip(types).foreach { case ((isMutable, ident), tpe) =>
        state.addLocalVariable(ident, tpe, isMutable)
      }
    }

    override def fillPlaceholder(expr: Ast.Const[Ctx]): Statement[Ctx] =
      throw Compiler.Error("Cannot define new variable in loop")

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      value.genCode(state) ++ idents.flatMap(p => state.genStoreCode(p._2)).reverse
    }
  }

  sealed trait UniqueDef {
    def name: String
  }

  object UniqueDef {
    def duplicates(defs: Seq[UniqueDef]): String = {
      defs
        .groupBy(_.name)
        .filter(_._2.size > 1)
        .keys
        .mkString(", ")
    }
  }

  final case class FuncDef[Ctx <: StatelessContext](
      annotations: Seq[Annotation],
      id: FuncId,
      isPublic: Boolean,
      useApprovedAssets: Boolean,
      useContractAssets: Boolean,
      args: Seq[Argument],
      rtypes: Seq[Type],
      body: Seq[Statement[Ctx]]
  ) extends UniqueDef {
    def name: String = id.name

    def signature: String = {
      val publicPrefix = if (isPublic) "pub " else ""
      val assetModifier = {
        (useApprovedAssets, useContractAssets) match {
          case (true, true) =>
            s"@use(approvedAssets=true,contractAssets=true) "
          case (true, false) =>
            s"@use(approvedAssets=true) "
          case (false, true) =>
            s"@use(contractAssets=true) "
          case (false, false) =>
            ""
        }
      }
      s"${assetModifier}${publicPrefix}${name}(${args.map(_.signature).mkString(",")})->(${rtypes.map(_.signature).mkString(",")})"
    }
    def getArgNames(): Seq[String]          = args.map(_.ident.name)
    def getArgTypeSignatures(): Seq[String] = args.map(_.tpe.signature)
    def getReturnSignatures(): Seq[String]  = rtypes.map(_.signature)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def checkRetTypes(stmt: Option[Statement[Ctx]]): Unit = {
      stmt match {
        case Some(_: ReturnStmt[Ctx]) => // we checked the `rtypes` in `ReturnStmt`
        case Some(IfElse(_, ifBranch, elseBranch)) =>
          checkRetTypes(ifBranch.lastOption)
          checkRetTypes(elseBranch.lastOption)
        case _ => throw new Compiler.Error(s"Expect return statement for function ${id.name}")
      }
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      state.checkArguments(args)
      args.foreach(arg => state.addLocalVariable(arg.ident, arg.tpe, arg.isMutable))
      body.foreach(_.check(state))
      if (rtypes.nonEmpty) checkRetTypes(body.lastOption)
    }

    def toMethod(state: Compiler.State[Ctx]): Method[Ctx] = {
      state.setFuncScope(id)
      check(state)

      val instrs    = body.flatMap(_.genCode(state))
      val localVars = state.getLocalVars(id)
      Method[Ctx](
        isPublic,
        useApprovedAssets,
        useContractAssets,
        argsLength = ArrayTransformer.flattenTypeLength(args.map(_.tpe)),
        localsLength = localVars.length,
        returnLength = ArrayTransformer.flattenTypeLength(rtypes),
        AVector.from(instrs)
      )
    }
  }

  object FuncDef {
    def main(
        stmts: Seq[Ast.Statement[StatefulContext]],
        useApprovedAssets: Boolean,
        useContractAssets: Boolean
    ): FuncDef[StatefulContext] = {
      FuncDef[StatefulContext](
        Seq.empty,
        id = FuncId("main", false),
        isPublic = true,
        useApprovedAssets = useApprovedAssets,
        useContractAssets = useContractAssets,
        args = Seq.empty,
        rtypes = Seq.empty,
        body = stmts
      )
    }
  }

  sealed trait AssignmentTarget[Ctx <: StatelessContext] extends Typed[Ctx, Type] {
    def name: String
    def getVariables(state: Compiler.State[Ctx]): Seq[Ident]
    def fillPlaceholder(expr: Const[Ctx]): AssignmentTarget[Ctx]
  }
  final case class AssignmentSimpleTarget[Ctx <: StatelessContext](ident: Ident)
      extends AssignmentTarget[Ctx] {
    def name: String = ident.name

    def _getType(state: Compiler.State[Ctx]): Type = state.getVariable(ident).tpe
    def getVariables(state: Compiler.State[Ctx]): Seq[Ident] =
      if (getType(state).isArrayType) state.getArrayRef(ident).vars else Seq(ident)
    def fillPlaceholder(expr: Const[Ctx]): AssignmentTarget[Ctx] = this
  }
  final case class AssignmentArrayElementTarget[Ctx <: StatelessContext](
      ident: Ident,
      indexes: Seq[Ast.Expr[Ctx]]
  ) extends AssignmentTarget[Ctx] {
    def name: String = ident.name

    @scala.annotation.tailrec
    private def elementType(indexes: Seq[Ast.Expr[Ctx]], tpe: Type): Type = {
      if (indexes.isEmpty) {
        tpe
      } else {
        tpe match {
          case arrayType: Type.FixedSizeArray =>
            elementType(indexes.drop(1), arrayType.baseType)
          case _ =>
            throw Compiler.Error(s"Invalid assignment to array: ${ident.name}")
        }
      }
    }

    def _getType(state: Compiler.State[Ctx]): Type = {
      elementType(indexes, state.getVariable(ident).tpe)
    }

    def getVariables(state: Compiler.State[Ctx]): Seq[Ident] = {
      val arrayRef = state.getArrayRef(ident)
      val idxes    = indexes.map(getConstantArrayIndex)
      getType(state) match {
        case _: Type.FixedSizeArray => arrayRef.subArray(idxes).vars
        case _                      => Seq(arrayRef.getVariable(idxes))
      }
    }

    def fillPlaceholder(expr: Const[Ctx]): AssignmentTarget[Ctx] = {
      val newIndexes = indexes.map(_.fillPlaceholder(expr))
      if (newIndexes == indexes) this else AssignmentArrayElementTarget(ident, newIndexes)
    }
  }

  final case class EventDef(
      id: TypeId,
      fields: Seq[EventField]
  ) extends UniqueDef {
    def name: String = id.name

    def signature: String = s"event ${id.name}(${fields.map(_.signature).mkString(",")})"

    def getFieldNames(): Seq[String]          = fields.map(_.ident.name)
    def getFieldTypeSignatures(): Seq[String] = fields.map(_.tpe.signature)
  }

  final case class EmitEvent[Ctx <: StatefulContext](id: TypeId, args: Seq[Expr[Ctx]])
      extends Statement[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Statement[Ctx] = {
      val newArgs = args.map(_.fillPlaceholder(expr))
      if (newArgs == args) this else EmitEvent(id, newArgs)
    }

    override def check(state: Compiler.State[Ctx]): Unit = {
      val eventInfo = state.getEvent(id)
      eventInfo.checkFieldTypes(args.flatMap(_.getType(state)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val eventIndex = {
        val index = state.eventsInfo.map(_.typeId).indexOf(id)
        // `check` method ensures that this event is defined
        assume(index >= 0)

        Const[Ctx](Val.I256(I256.from(index))).genCode(state)
      }
      val argsType = args.flatMap(_.getType(state))
      if (argsType.exists(_.isArrayType)) {
        throw Compiler.Error(s"Array type not supported for event ${id.name}")
      }
      val logOpCode = Compiler.genLogs(args.length)
      eventIndex ++ args.flatMap(_.genCode(state)) :+ logOpCode
    }
  }

  final case class Assign[Ctx <: StatelessContext](
      targets: Seq[AssignmentTarget[Ctx]],
      rhs: Expr[Ctx]
  ) extends Statement[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Statement[Ctx] = {
      val newTargets = targets.map(_.fillPlaceholder(expr))
      val newRhs     = rhs.fillPlaceholder(expr)
      if (newTargets == targets && newRhs == rhs) this else Assign(newTargets, newRhs)
    }

    override def check(state: Compiler.State[Ctx]): Unit = {
      val leftTypes  = targets.map(_.getType(state))
      val rightTypes = rhs.getType(state)
      if (leftTypes != rightTypes) {
        throw Compiler.Error(s"Assign $rightTypes to $leftTypes")
      }
      targets.foreach { target =>
        target.getVariables(state).foreach { ident =>
          if (!state.getVariable(ident).isMutable) {
            throw Compiler.Error(s"Assign to immutable variable: ${target.name}")
          }
        }
      }
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val variables  = targets.flatMap(_.getVariables(state))
      val storeCodes = variables.flatMap(state.genStoreCode).reverse
      rhs.genCode(state) ++ storeCodes
    }
  }
  final case class FuncCall[Ctx <: StatelessContext](id: FuncId, args: Seq[Expr[Ctx]])
      extends Statement[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Statement[Ctx] = {
      val newArgs = args.map(_.fillPlaceholder(expr))
      if (newArgs == args) this else FuncCall(id, newArgs)
    }

    override def check(state: Compiler.State[Ctx]): Unit = {
      val funcInfo = state.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
      ()
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val func       = state.getFunc(id)
      val argsType   = args.flatMap(_.getType(state))
      val returnType = func.getReturnType(argsType)
      args.flatMap(_.genCode(state)) ++
        (if (func.isVariadic) Seq(U256Const(Val.U256(U256.unsafe(args.length)))) else Seq.empty) ++
        func.genCode(argsType) ++
        Seq.fill(ArrayTransformer.flattenTypeLength(returnType))(Pop)
    }
  }
  final case class ContractCall(
      obj: Expr[StatefulContext],
      callId: FuncId,
      args: Seq[Expr[StatefulContext]]
  ) extends Statement[StatefulContext]
      with ContractCallBase {
    override def fillPlaceholder(expr: Const[StatefulContext]): Statement[StatefulContext] = {
      val newObj  = obj.fillPlaceholder(expr)
      val newArgs = args.map(_.fillPlaceholder(expr))
      if (newObj == obj && newArgs == args) this else ContractCall(newObj, callId, newArgs)
    }

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
        Seq.fill[Instr[StatefulContext]](ArrayTransformer.flattenTypeLength(returnType))(Pop)
    }
  }
  final case class IfElse[Ctx <: StatelessContext](
      condition: Expr[Ctx],
      ifBranch: Seq[Statement[Ctx]],
      elseBranch: Seq[Statement[Ctx]]
  ) extends Statement[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Statement[Ctx] = {
      val newCondition  = condition.fillPlaceholder(expr)
      val newIfBranch   = ifBranch.map(_.fillPlaceholder(expr))
      val newElseBranch = elseBranch.map(_.fillPlaceholder(expr))
      if (
        newCondition == condition &&
        newIfBranch == ifBranch &&
        newElseBranch == elseBranch
      ) {
        this
      } else {
        IfElse(newCondition, newIfBranch, newElseBranch)
      }
    }

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
    override def fillPlaceholder(expr: Const[Ctx]): Statement[Ctx] = {
      val newCondition = condition.fillPlaceholder(expr)
      val newBody      = body.map(_.fillPlaceholder(expr))
      if (newCondition == condition && newBody == body) this else While(newCondition, newBody)
    }

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
    override def fillPlaceholder(expr: Const[Ctx]): Statement[Ctx] =
      throw Compiler.Error("Cannot return in loop")

    override def check(state: Compiler.State[Ctx]): Unit = {
      state.checkReturn(exprs.flatMap(_.getType(state)))
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      exprs.flatMap(_.genCode(state)) :+ Return
  }
  final case class Loop[Ctx <: StatelessContext](
      start: Int,
      end: Int,
      step: Int,
      body: Statement[Ctx]
  ) extends Statement[Ctx] {
    override def fillPlaceholder(expr: Const[Ctx]): Statement[Ctx] =
      throw Compiler.Error("Nested loops are not supported")

    private var _statements: Option[Seq[Statement[Ctx]]] = None
    private def getStatements(state: Compiler.State[Ctx]): Seq[Statement[Ctx]] = {
      _statements match {
        case Some(stats) => stats
        case None =>
          if (step == 0) throw Compiler.Error("loop step cannot be 0")
          val range = start.until(end, step)
          if (range.size > state.config.loopUnrollingLimit) {
            throw Compiler.Error("loop range too large")
          }
          val stats = range.map { index =>
            val expr = Ast.Const[Ctx](Val.U256(U256.unsafe(index)))
            body.fillPlaceholder(expr)
          }
          _statements = Some(stats)
          stats
      }
    }

    override def check(state: Compiler.State[Ctx]): Unit =
      getStatements(state).foreach(_.check(state))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      getStatements(state).flatMap(_.genCode(state))
  }

  trait Contract[Ctx <: StatelessContext] {
    def ident: TypeId
    def templateVars: Seq[Argument]
    def fields: Seq[Argument]
    def funcs: Seq[FuncDef[Ctx]]

    def builtInContractFuncs(): Seq[Compiler.ContractFunc[Ctx]]

    lazy val funcTable: Map[FuncId, Compiler.ContractFunc[Ctx]] = {
      val builtInFuncs = builtInContractFuncs()
      var table = Compiler.SimpleFunc
        .from(funcs)
        .map(f => f.id -> f)
        .toMap[FuncId, Compiler.ContractFunc[Ctx]]
      builtInFuncs.foreach(func => table = table + (FuncId(func.name, isBuiltIn = true) -> func))
      if (table.size != (funcs.size + builtInFuncs.length)) {
        val duplicates = UniqueDef.duplicates(funcs)
        throw Compiler.Error(s"These functions are defined multiple times: $duplicates")
      }
      table
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      state.checkArguments(fields)
      templateVars.zipWithIndex.foreach { case (temp, index) =>
        state.addTemplateVariable(temp.ident, temp.tpe, index)
      }
      fields.foreach(field => state.addFieldVariable(field.ident, field.tpe, field.isMutable))
    }

    def genCode(state: Compiler.State[Ctx]): VmContract[Ctx]
  }

  final case class AssetScript(
      ident: TypeId,
      templateVars: Seq[Argument],
      funcs: Seq[FuncDef[StatelessContext]]
  ) extends Contract[StatelessContext] {
    val fields: Seq[Argument] = Seq.empty

    def builtInContractFuncs(): Seq[Compiler.ContractFunc[StatelessContext]] = Seq.empty

    def genCode(state: Compiler.State[StatelessContext]): StatelessScript = {
      check(state)
      val methods = AVector.from(funcs.view.map(func => func.toMethod(state)))
      StatelessScript.from(methods).getOrElse(throw Compiler.Error("Empty methods"))
    }
  }

  sealed trait ContractWithState extends Contract[StatefulContext] {
    def ident: TypeId
    def name: String = ident.name
    def inheritances: Seq[Inheritance]

    def templateVars: Seq[Argument]
    def fields: Seq[Argument]
    def events: Seq[EventDef]

    def builtInContractFuncs(): Seq[Compiler.ContractFunc[StatefulContext]] = Seq(loadFieldsFunc)
    private val loadFieldsFunc: Compiler.ContractFunc[StatefulContext] =
      new Compiler.ContractFunc[StatefulContext] {
        def name: String      = "loadFields"
        def isPublic: Boolean = true

        lazy val returnType: Seq[Type] = fields.map(_.tpe)

        def getReturnType(inputType: Seq[Type]): Seq[Type] = {
          if (inputType.isEmpty) {
            returnType
          } else {
            throw Compiler.Error(s"Built-in function loadFields does not need any argument")
          }
        }

        def genCode(inputType: Seq[Type]): Seq[Instr[StatefulContext]] = {
          throw Compiler.Error(s"Built-in function loadFields should be external call")
        }

        def genExternalCallCode(typeId: TypeId): Seq[Instr[StatefulContext]] =
          Seq(LoadContractFields)
      }

    def eventsInfo(): Seq[Compiler.EventInfo] = {
      if (events.distinctBy(_.id).size != events.size) {
        val duplicates = UniqueDef.duplicates(events)
        throw Compiler.Error(s"These events are defined multiple times: $duplicates")
      }
      events.map { event =>
        Compiler.EventInfo(event.id, event.fields.map(_.tpe))
      }
    }
  }

  final case class TxScript(
      ident: TypeId,
      templateVars: Seq[Argument],
      funcs: Seq[FuncDef[StatefulContext]]
  ) extends ContractWithState {
    val fields: Seq[Argument]                  = Seq.empty
    val events: Seq[EventDef]                  = Seq.empty
    val inheritances: Seq[ContractInheritance] = Seq.empty

    def getTemplateVarsSignature(): String =
      s"TxScript ${name}(${templateVars.map(_.signature).mkString(",")})"
    def getTemplateVarsNames(): Seq[String] = templateVars.map(_.ident.name)
    def getTemplateVarsTypes(): Seq[String] = templateVars.map(_.tpe.signature)

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

  sealed trait Inheritance {
    def parentId: TypeId
  }
  final case class ContractInheritance(parentId: TypeId, idents: Seq[Ident]) extends Inheritance
  final case class InterfaceInheritance(parentId: TypeId)                    extends Inheritance
  final case class TxContract(
      ident: TypeId,
      templateVars: Seq[Argument],
      fields: Seq[Argument],
      funcs: Seq[FuncDef[StatefulContext]],
      events: Seq[EventDef],
      inheritances: Seq[Inheritance]
  ) extends ContractWithState {
    def getFieldsSignature(): String =
      s"TxContract ${name}(${fields.map(_.signature).mkString(",")})"
    def getFieldNames(): Seq[String] = fields.map(_.ident.name)
    def getFieldTypes(): Seq[String] = fields.map(_.tpe.signature)

    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      check(state)
      StatefulContract(
        ArrayTransformer.flattenTypeLength(fields.map(_.tpe)),
        AVector.from(funcs.view.map(_.toMethod(state)))
      )
    }
  }

  final case class ContractInterface(
      ident: TypeId,
      funcs: Seq[FuncDef[StatefulContext]],
      events: Seq[EventDef],
      inheritances: Seq[InterfaceInheritance]
  ) extends ContractWithState {
    def error(tpe: String): Compiler.Error =
      new Compiler.Error(s"Interface ${ident.name} does not contain any $tpe")

    def templateVars: Seq[Argument]  = throw error("template variable")
    def fields: Seq[Argument]        = throw error("field")
    def getFieldsSignature(): String = throw error("field")
    def getFieldTypes(): Seq[String] = throw error("field")

    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      throw new Compiler.Error(s"Interface ${ident.name} does not generate code")
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

    private def getContract(typeId: TypeId): ContractWithState = {
      contracts.find(_.ident == typeId) match {
        case None              => throw Compiler.Error(s"Contract $typeId does not exist")
        case Some(_: TxScript) => throw Compiler.Error(s"Expect contract $typeId, but got script")
        case Some(contract: ContractWithState) => contract
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def buildDependencies(
        contract: ContractWithState,
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        visited: mutable.Set[TypeId]
    ): Unit = {
      if (!visited.add(contract.ident)) {
        throw Compiler.Error(s"Cyclic inheritance detected for contract ${contract.ident.name}")
      }

      val allParents = mutable.Map.empty[TypeId, ContractWithState]
      contract.inheritances.foreach { inheritance =>
        val parentId       = inheritance.parentId
        val parentContract = getContract(parentId)
        MultiTxContract.checkInheritanceFields(contract, inheritance, parentContract)

        allParents += parentId -> parentContract
        if (!parentsCache.contains(parentId)) {
          buildDependencies(parentContract, parentsCache, visited)
        }
        parentsCache(parentId).foreach { grandParent =>
          allParents += grandParent.ident -> grandParent
        }
      }
      parentsCache += contract.ident -> allParents.values.toSeq
    }

    private def buildDependencies(): mutable.Map[TypeId, Seq[ContractWithState]] = {
      val parentsCache = mutable.Map.empty[TypeId, Seq[ContractWithState]]
      val visited      = mutable.Set.empty[TypeId]
      contracts.foreach {
        case _: TxScript => ()
        case contract =>
          if (!parentsCache.contains(contract.ident)) {
            buildDependencies(contract, parentsCache, visited)
          }
      }
      parentsCache
    }

    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def extendedContracts(): MultiTxContract = {
      val parentsCache = buildDependencies()
      val newContracts: Seq[ContractWithState] = contracts.map {
        case script: TxScript =>
          script
        case c: TxContract =>
          val (funcs, events) = MultiTxContract.extractFuncsAndEvents(parentsCache, c)
          TxContract(
            c.ident,
            c.templateVars,
            c.fields,
            funcs,
            events,
            c.inheritances
          )
        case i: ContractInterface =>
          val (funcs, events) = MultiTxContract.extractFuncsAndEvents(parentsCache, i)
          ContractInterface(i.ident, funcs, events, i.inheritances)
      }
      MultiTxContract(newContracts)
    }

    def genStatefulScript(
        config: CompilerConfig,
        contractIndex: Int
    ): (StatefulScript, TxScript) = {
      val state = Compiler.State.buildFor(config, this, contractIndex)
      get(contractIndex) match {
        case script: TxScript => (script.genCode(state), script)
        case _: TxContract => throw Compiler.Error(s"The code is for TxContract, not for TxScript")
        case _: ContractInterface =>
          throw Compiler.Error(s"The code is for Interface, not for TxScript")
      }
    }

    def genStatefulContract(
        config: CompilerConfig,
        contractIndex: Int
    ): (StatefulContract, TxContract) = {
      val state = Compiler.State.buildFor(config, this, contractIndex)
      get(contractIndex) match {
        case contract: TxContract => (contract.genCode(state), contract)
        case _: TxScript => throw Compiler.Error(s"The code is for TxScript, not for TxContract")
        case _: ContractInterface =>
          throw Compiler.Error(s"The code is for Interface, not for TxContract")
      }
    }
  }

  object MultiTxContract {
    def checkInheritanceFields(
        contract: ContractWithState,
        inheritance: Inheritance,
        parentContract: ContractWithState
    ): Unit = {
      inheritance match {
        case i: ContractInheritance => _checkInheritanceFields(contract, i, parentContract)
        case _                      => ()
      }
    }
    private def _checkInheritanceFields(
        contract: ContractWithState,
        inheritance: ContractInheritance,
        parentContract: ContractWithState
    ): Unit = {
      val fields = inheritance.idents.map { ident =>
        contract.fields
          .find(_.ident == ident)
          .getOrElse(
            throw Compiler.Error(s"Contract field ${ident.name} does not exist")
          )
      }
      if (fields != parentContract.fields) {
        throw Compiler.Error(
          s"Invalid contract inheritance fields, expect ${parentContract.fields}, have $fields"
        )
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def extractFuncsAndEvents(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        contract: ContractWithState
    ): (Seq[FuncDef[StatefulContext]], Seq[EventDef]) = {
      val parents = parentsCache(contract.ident)
      val (_allContracts, _allInterfaces) =
        (parents :+ contract).partition(_.isInstanceOf[TxContract])
      val allContracts = _allContracts.sortBy(_.ident.name)
      val allInterfaces =
        sortInterfaces(parentsCache, _allInterfaces.map(_.asInstanceOf[ContractInterface]))

      val _contractFuncs = allContracts.flatMap(_.funcs)
      val interfaceFuncs = allInterfaces.flatMap(_.funcs)
      val isTxContract   = contract.isInstanceOf[TxContract]
      val contractFuncs  = checkInterfaceFuncs(_contractFuncs, interfaceFuncs, isTxContract)

      val contractEvents = allContracts.flatMap(_.events)
      val events         = allInterfaces.flatMap(_.events) ++ contractEvents

      val resultFuncs = if (isTxContract) {
        contractFuncs
      } else {
        require(contractFuncs.isEmpty)
        interfaceFuncs
      }
      (resultFuncs, events)
    }

    private def sortInterfaces(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        allInterfaces: Seq[ContractInterface]
    ): Seq[ContractInterface] = {
      allInterfaces.sortBy(interface => parentsCache(interface.ident).length)
    }

    private def checkInterfaceFuncs(
        contractFuncs: Seq[FuncDef[StatefulContext]],
        interfaceFuncs: Seq[FuncDef[StatefulContext]],
        isTxContract: Boolean
    ): Seq[FuncDef[StatefulContext]] = {
      val contractFuncSet   = contractFuncs.view.map(f => f.id.name -> f).toMap
      val interfaceFuncsSet = interfaceFuncs.view.map(f => f.id.name -> f).toMap
      if (contractFuncSet.size != contractFuncs.size) {
        val duplicates = UniqueDef.duplicates(contractFuncs)
        throw Compiler.Error(s"These functions are defined multiple times: $duplicates")
      } else if (interfaceFuncsSet.size != interfaceFuncs.size) {
        val duplicates = UniqueDef.duplicates(interfaceFuncs)
        throw Compiler.Error(s"These functions are defined multiple times: $duplicates")
      } else if (isTxContract) {
        val unimplemented = interfaceFuncsSet.keys.filter(!contractFuncSet.contains(_))
        if (unimplemented.nonEmpty) {
          throw new Compiler.Error(s"Functions are unimplemented: ${unimplemented.mkString(",")}")
        }
        interfaceFuncsSet.foreach { case (name, interfaceFunc) =>
          val contractFunc = contractFuncSet(name)
          if (contractFunc.copy(body = Seq.empty) != interfaceFunc) {
            throw new Compiler.Error(s"Function ${name} is implemented with wrong signature")
          }
        }
      }

      if (isTxContract) {
        val sortedContractFuncSet = interfaceFuncs.map(f => contractFuncSet(f.id.name)) ++
          contractFuncs.filter(f => !interfaceFuncsSet.contains(f.id.name))
        sortedContractFuncSet
      } else {
        contractFuncs
      }
    }
  }
}
// scalastyle:on number.of.methods number.of.types
