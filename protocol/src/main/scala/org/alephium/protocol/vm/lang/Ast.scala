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

import org.alephium.protocol.vm.{Contract => VmContract, _}
import org.alephium.protocol.vm.lang.LogicalOperator.Not
import org.alephium.util.{AVector, I256, U256}

// scalastyle:off number.of.methods number.of.types file.size.limit
object Ast {
  final case class Ident(name: String)
  final case class TypeId(name: String)
  final case class FuncId(name: String, isBuiltIn: Boolean)
  final case class Argument(ident: Ident, tpe: Type, isMutable: Boolean, isUnused: Boolean) {
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

  def funcName(typeId: TypeId, funcId: FuncId): String = quote(s"${typeId.name}.${funcId.name}")

  final case class ApproveAsset[Ctx <: StatelessContext](
      address: Expr[Ctx],
      attoAlphAmountOpt: Option[Expr[Ctx]],
      tokenAmounts: Seq[(Expr[Ctx], Expr[Ctx])]
  ) {
    lazy val approveCount = (if (attoAlphAmountOpt.isEmpty) 0 else 1) + tokenAmounts.length

    def check(state: Compiler.State[Ctx]): Unit = {
      if (address.getType(state) != Seq(Type.Address)) {
        throw Compiler.Error(s"Invalid address type: ${address}")
      }
      if (attoAlphAmountOpt.exists(_.getType(state) != Seq(Type.U256))) {
        throw Compiler.Error(s"Invalid amount type: ${attoAlphAmountOpt}")
      }
      if (
        tokenAmounts
          .exists(p =>
            (p._1.getType(state), p._2.getType(state)) != (Seq(Type.ByteVec), Seq(Type.U256))
          )
      ) {
        throw Compiler.Error(s"Invalid token amount type: ${tokenAmounts}")
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      assume(approveCount >= 1)
      val approveAlph: Seq[Instr[Ctx]] = attoAlphAmountOpt match {
        case Some(amount) => amount.genCode(state) :+ ApproveAlph.asInstanceOf[Instr[Ctx]]
        case None         => Seq.empty
      }
      val approveTokens: Seq[Instr[Ctx]] = tokenAmounts.flatMap { case (tokenId, amount) =>
        tokenId.genCode(state) ++ amount.genCode(state) :+ ApproveToken.asInstanceOf[Instr[Ctx]]
      }
      address.genCode(state) ++ Seq.fill(approveCount - 1)(Dup) ++ approveAlph ++ approveTokens
    }
  }

  trait ApproveAssets[Ctx <: StatelessContext] {
    def approveAssets: Seq[ApproveAsset[Ctx]]

    def checkApproveAssets(state: Compiler.State[Ctx]): Unit = {
      approveAssets.foreach(_.check(state))
    }

    def genApproveCode(
        state: Compiler.State[Ctx],
        func: Compiler.FuncInfo[Ctx]
    ): Seq[Instr[Ctx]] = {
      (approveAssets.nonEmpty, func.usePreapprovedAssets) match {
        case (true, false) =>
          throw Compiler.Error(s"Function `${func.name}` does not use preapproved assets")
        case (false, true) =>
          throw Compiler.Error(
            s"Function `${func.name}` needs preapproved assets, please use braces syntax"
          )
        case _ => ()
      }
      approveAssets.flatMap(_.genCode(state))
    }
  }
  object ContractAssets {
    val contractAssetsInstrs: Set[Instr[_]] =
      Set(
        TransferAlphFromSelf,
        TransferTokenFromSelf,
        TransferAlphToSelf,
        TransferTokenToSelf,
        DestroySelf,
        SelfAddress
      )

    def checkCodeUsingContractAssets[Ctx <: StatelessContext](
        instrs: Seq[Instr[Ctx]],
        useAssetsInContract: Boolean,
        typeId: TypeId,
        funcId: FuncId
    ): Unit = {
      if (useAssetsInContract && !instrs.exists(contractAssetsInstrs.contains(_))) {
        throw Compiler.Error(
          s"Function ${funcName(typeId, funcId)} does not use contract assets, but its annotation of contract assets is turn on"
        )
      }
    }
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
  }

  final case class Const[Ctx <: StatelessContext](v: Val) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(Type.fromVal(v.tpe))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      Seq(v.toConstInstr)
    }
  }
  final case class CreateArrayExpr[Ctx <: StatelessContext](elements: Seq[Expr[Ctx]])
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type.FixedSizeArray] = {
      assume(elements.nonEmpty)
      val baseType = elements(0).getType(state)
      if (baseType.length != 1) {
        throw Compiler.Error(s"Expected single type for array element, got ${quote(elements)}")
      }
      if (elements.drop(0).exists(_.getType(state) != baseType)) {
        throw Compiler.Error(s"Array elements should have same type, got ${quote(elements)}")
      }
      Seq(Type.FixedSizeArray(baseType(0), elements.size))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      elements.flatMap(_.genCode(state))
    }
  }
  final case class ArrayElement[Ctx <: StatelessContext](
      array: Expr[Ctx],
      indexes: Seq[Ast.Expr[Ctx]]
  ) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      Seq(state.getArrayElementType(array, indexes))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val (arrayRef, codes) = state.getOrCreateArrayRef(array)
      getType(state) match {
        case Seq(_: Type.FixedSizeArray) =>
          codes ++ arrayRef.subArray(state, indexes).genLoadCode(state)
        case _ =>
          codes ++ arrayRef.genLoadCode(state, indexes)
      }
    }
  }
  final case class Variable[Ctx <: StatelessContext](id: Ident) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(state.getType(id))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.genLoadCode(id)
    }
  }
  final case class EnumFieldSelector[Ctx <: StatelessContext](enumId: TypeId, field: Ident)
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] =
      Seq(state.getVariable(EnumDef.fieldIdent(enumId, field)).tpe)

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val ident = EnumDef.fieldIdent(enumId, field)
      state.genLoadCode(ident)
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
      state.checkContractType(contractType)

      if (address.getType(state) != Seq(Type.ByteVec)) {
        throw Compiler.Error(s"Invalid expr $address for contract address")
      }

      if (!state.getContractInfo(contractType).kind.instantiable) {
        throw Compiler.Error(s"${contractType.name} is not instantiable")
      }

      Seq(Type.Contract.stack(contractType))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      address.genCode(state)
  }
  final case class CallExpr[Ctx <: StatelessContext](
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends Expr[Ctx]
      with ApproveAssets[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      checkApproveAssets(state)
      val funcInfo = state.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.addInternalCall(id)
      val func = state.getFunc(id)
      genApproveCode(state, func) ++
        args.flatMap(_.genCode(state)) ++
        (if (func.isVariadic) Seq(U256Const(Val.U256.unsafe(args.length))) else Seq.empty) ++
        func.genCode(args.flatMap(_.getType(state)))
    }
  }

  trait ContractCallBase extends ApproveAssets[StatefulContext] {
    def obj: Expr[StatefulContext]
    def callId: FuncId
    def args: Seq[Expr[StatefulContext]]

    def _getTypeBase(state: Compiler.State[StatefulContext]): Seq[Type] = {
      val objType = obj.getType(state)
      if (objType.length != 1) {
        throw Compiler.Error(s"Expected a single parameter for contract object, got ${quote(obj)}")
      } else {
        objType(0) match {
          case contract: Type.Contract =>
            val funcInfo = state.getFunc(contract.id, callId)
            state.addExternalCall(contract.id, callId)
            funcInfo.getReturnType(args.flatMap(_.getType(state)))
          case _ =>
            throw Compiler.Error(s"Expected a contract for ${quote(callId)}, got ${quote(obj)}")
        }
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genContractCall(
        state: Compiler.State[StatefulContext],
        popReturnValues: Boolean
    ): Seq[Instr[StatefulContext]] = {
      val contract  = obj.getType(state)(0).asInstanceOf[Type.Contract]
      val func      = state.getFunc(contract.id, callId)
      val argLength = Type.flattenTypeLength(func.argsType)
      val retLength = func.getReturnLength(args.flatMap(_.getType(state)))
      genApproveCode(state, func) ++
        args.flatMap(_.genCode(state)) ++
        Seq(
          ConstInstr.u256(Val.U256(U256.unsafe(argLength))),
          ConstInstr.u256(Val.U256(U256.unsafe(retLength)))
        ) ++
        obj.genCode(state) ++
        func.genExternalCallCode(contract.id) ++
        (if (popReturnValues) Seq.fill[Instr[StatefulContext]](retLength)(Pop) else Seq.empty)
    }
  }
  final case class ContractCallExpr(
      obj: Expr[StatefulContext],
      callId: FuncId,
      approveAssets: Seq[ApproveAsset[StatefulContext]],
      args: Seq[Expr[StatefulContext]]
  ) extends Expr[StatefulContext]
      with ContractCallBase {
    override def _getType(state: Compiler.State[StatefulContext]): Seq[Type] = {
      checkApproveAssets(state)
      _getTypeBase(state)
    }

    override def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      genContractCall(state, false)
    }
  }
  final case class ParenExpr[Ctx <: StatelessContext](expr: Expr[Ctx]) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] =
      expr.getType(state: Compiler.State[Ctx])

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      expr.genCode(state)
  }

  trait IfBranch[Ctx <: StatelessContext] {
    def condition: Expr[Ctx]
    def checkCondition(state: Compiler.State[Ctx]): Unit = {
      val conditionType = condition.getType(state)
      if (conditionType != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr: $conditionType")
      }
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  trait ElseBranch[Ctx <: StatelessContext] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  trait IfElse[Ctx <: StatelessContext] {
    def ifBranches: Seq[IfBranch[Ctx]]
    def elseBranchOpt: Option[ElseBranch[Ctx]]

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val ifBranchesIRs = Array.ofDim[Seq[Instr[Ctx]]](ifBranches.length + 1)
      val elseOffsets   = Array.ofDim[Int](ifBranches.length + 1)
      val elseBodyIRs   = elseBranchOpt.map(_.genCode(state)).getOrElse(Seq.empty)
      ifBranchesIRs(ifBranches.length) = elseBodyIRs
      elseOffsets(ifBranches.length) = elseBodyIRs.length
      ifBranches.zipWithIndex.view.reverse.foreach { case (ifBranch, index) =>
        val initialOffset    = elseOffsets(index + 1)
        val notTheLastBranch = index < ifBranches.length - 1 || elseBranchOpt.nonEmpty

        val bodyIRsWithoutOffset = ifBranch.genCode(state)
        val bodyOffsetIR = if (notTheLastBranch) {
          Seq(Jump(initialOffset))
        } else {
          Seq.empty
        }
        val bodyIRs = bodyIRsWithoutOffset ++ bodyOffsetIR

        val conditionOffset =
          if (notTheLastBranch) bodyIRs.length else bodyIRs.length + initialOffset
        val conditionIRs = Statement.getCondIR(ifBranch.condition, state, conditionOffset)
        ifBranchesIRs(index) = conditionIRs ++ bodyIRs
        elseOffsets(index) = initialOffset + bodyIRs.length + conditionIRs.length
      }
      ifBranchesIRs.reduce(_ ++ _)
    }
  }

  final case class IfBranchExpr[Ctx <: StatelessContext](
      condition: Expr[Ctx],
      expr: Expr[Ctx]
  ) extends IfBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = expr.genCode(state)
  }
  final case class ElseBranchExpr[Ctx <: StatelessContext](
      expr: Expr[Ctx]
  ) extends ElseBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = expr.genCode(state)
  }
  final case class IfElseExpr[Ctx <: StatelessContext](
      ifBranches: Seq[IfBranchExpr[Ctx]],
      elseBranch: ElseBranchExpr[Ctx]
  ) extends IfElse[Ctx]
      with Expr[Ctx] {
    def elseBranchOpt: Option[ElseBranch[Ctx]] = Some(elseBranch)

    def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      val elseBranchType = elseBranch.expr.getType(state)
      ifBranches.foreach { ifBranch =>
        ifBranch.checkCondition(state)
        val ifBranchType = ifBranch.expr.getType(state)
        if (ifBranchType != elseBranchType) {
          throw Compiler.Error(
            s"Invalid types of if-else expression branches, expected ${quote(elseBranchType)}, got ${quote(ifBranchType)}"
          )
        }
      }
      elseBranchType
    }
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

  sealed trait VarDeclaration
  final case class NamedVar(mutable: Boolean, ident: Ident) extends VarDeclaration
  case object AnonymousVar                                  extends VarDeclaration

  final case class VarDef[Ctx <: StatelessContext](
      vars: Seq[VarDeclaration],
      value: Expr[Ctx]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      val types = value.getType(state)
      if (types.length != vars.length) {
        throw Compiler.Error(
          s"Invalid variable declaration, expected ${types.length} variables, got ${vars.length} variables"
        )
      }
      vars.zip(types).foreach {
        case (NamedVar(isMutable, ident), tpe) =>
          state.addLocalVariable(ident, tpe, isMutable, isUnused = false, isGenerated = false)
        case _ =>
      }
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val storeCodes = vars.zip(value.getType(state)).flatMap {
        case (NamedVar(_, ident), _) => state.genStoreCode(ident)
        case (AnonymousVar, tpe: Type.FixedSizeArray) =>
          Seq(Seq.fill(tpe.flattenSize())(Pop))
        case (AnonymousVar, _) => Seq(Seq(Pop))
      }
      value.genCode(state) ++ storeCodes.reverse.flatten
    }
  }

  trait UniqueDef {
    def name: String
  }

  object UniqueDef {
    def checkDuplicates(defs: Seq[UniqueDef], name: String): Unit = {
      if (defs.distinctBy(_.name).size != defs.size) {
        throw Compiler.Error(s"These $name are defined multiple times: ${duplicates(defs)}")
      }
    }

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
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      useExternalCallCheck: Boolean,
      useReadonly: Boolean,
      args: Seq[Argument],
      rtypes: Seq[Type],
      bodyOpt: Option[Seq[Statement[Ctx]]]
  ) extends UniqueDef {
    def name: String              = id.name
    def isPrivate: Boolean        = !isPublic
    val body: Seq[Statement[Ctx]] = bodyOpt.getOrElse(Seq.empty)

    private var usedVars: Option[Set[String]] = None

    def signature: String = {
      val publicPrefix = if (isPublic) "pub " else ""
      val assetModifier = {
        (usePreapprovedAssets, useAssetsInContract) match {
          case (true, true) =>
            s"@using(preapprovedAssets=true,assetsInContract=true) "
          case (true, false) =>
            s"@using(preapprovedAssets=true) "
          case (false, true) =>
            s"@using(assetsInContract=true) "
          case (false, false) =>
            ""
        }
      }
      s"${assetModifier}${publicPrefix}${name}(${args.map(_.signature).mkString(",")})->(${rtypes.map(_.signature).mkString(",")})"
    }
    def getArgNames(): AVector[String]          = AVector.from(args.view.map(_.ident.name))
    def getArgTypeSignatures(): AVector[String] = AVector.from(args.view.map(_.tpe.signature))
    def getArgMutability(): AVector[Boolean]    = AVector.from(args.view.map(_.isMutable))
    def getReturnSignatures(): AVector[String]  = AVector.from(rtypes.view.map(_.signature))

    def hasDirectExternalCallCheck(): Boolean = {
      !useExternalCallCheck || // external call check manually disabled
      body.exists {
        case FuncCall(id, _, _) => id.isBuiltIn && id.name == "checkCaller"
        case _                  => false
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def checkRetTypes(stmt: Option[Statement[Ctx]]): Unit = {
      stmt match {
        case Some(_: ReturnStmt[Ctx]) => () // we checked the `rtypes` in `ReturnStmt`
        case Some(IfElseStatement(ifBranches, elseBranchOpt)) =>
          ifBranches.foreach(branch => checkRetTypes(branch.body.lastOption))
          checkRetTypes(elseBranchOpt.flatMap(_.body.lastOption))
        case Some(call: FuncCall[_]) if call.id == FuncId("panic", isBuiltIn = true) => ()
        case _ =>
          throw new Compiler.Error(s"Expected return statement for function ${quote(id.name)}")
      }
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      state.checkArguments(args)
      args.foreach(arg =>
        state.addLocalVariable(arg.ident, arg.tpe, arg.isMutable, arg.isUnused, isGenerated = false)
      )
      usedVars match {
        case Some(vars) => // the function has been compiled before
          state.addUsedVars(vars)
          body.foreach(_.check(state))
        case None =>
          val prevUsedVars = mutable.Set.from(state.usedVars)
          body.foreach(_.check(state))
          usedVars = Some(Set.from(state.usedVars.diff(prevUsedVars)))
      }
      state.checkUnusedLocalVars(id)
      if (rtypes.nonEmpty) checkRetTypes(body.lastOption)
    }

    def checkReadonly(state: Compiler.State[Ctx], instrs: AVector[Instr[Ctx]]): Unit = {
      val changeState = instrs.exists {
        case _: StoreField | _: StoreFieldByIndex.type | _: LogInstr => true
        case _                                                       => false
      }
      val internalCalls        = state.internalCalls.getOrElse(id, mutable.Set.empty)
      val invalidInternalCalls = internalCalls.filterNot(state.getFunc(_).isReadonly)
      val externalCalls        = state.externalCalls.getOrElse(id, mutable.Set.empty)
      val invalidExternalCalls = externalCalls.filterNot { case (typeId, funcId) =>
        state.getFunc(typeId, funcId).isReadonly
      }

      val isReadonly =
        !changeState && invalidInternalCalls.isEmpty && invalidExternalCalls.isEmpty
      if (!isReadonly && useReadonly) {
        if (changeState) {
          throw Compiler.Error(s"Readonly function ${funcName(state.typeId, id)} changes state")
        }
        if (invalidInternalCalls.nonEmpty) {
          throw Compiler.Error(
            s"Readonly function ${funcName(state.typeId, id)} have invalid internal calls: ${quote(
                invalidInternalCalls.map(_.name).mkString(", ")
              )}"
          )
        }
        if (invalidExternalCalls.nonEmpty) {
          val msg = invalidExternalCalls
            .map { case (typeId, funcId) =>
              s"${typeId.name}.${funcId.name}"
            }
            .mkString(", ")
          throw Compiler.Error(
            s"Readonly function ${funcName(state.typeId, id)} have invalid external calls: ${quote(msg)}"
          )
        }
      }

      if (isReadonly && !useReadonly) {
        state.warnReadonlyCheck(state.typeId, id)
      }
    }

    def toMethod(state: Compiler.State[Ctx]): Method[Ctx] = {
      state.setFuncScope(id)
      check(state)

      val instrs    = body.flatMap(_.genCode(state))
      val localVars = state.getLocalVars(id)
      ContractAssets.checkCodeUsingContractAssets(instrs, useAssetsInContract, state.typeId, id)
      Method[Ctx](
        isPublic,
        usePreapprovedAssets,
        useAssetsInContract,
        argsLength = Type.flattenTypeLength(args.map(_.tpe)),
        localsLength = localVars.length,
        returnLength = Type.flattenTypeLength(rtypes),
        AVector.from(instrs)
      )
    }
  }

  object FuncDef {
    def main(
        stmts: Seq[Ast.Statement[StatefulContext]],
        usePreapprovedAssets: Boolean,
        useAssetsInContract: Boolean,
        useReadonly: Boolean
    ): FuncDef[StatefulContext] = {
      FuncDef[StatefulContext](
        Seq.empty,
        id = FuncId("main", false),
        isPublic = true,
        usePreapprovedAssets = usePreapprovedAssets,
        useAssetsInContract = useAssetsInContract,
        useExternalCallCheck = true,
        useReadonly = useReadonly,
        args = Seq.empty,
        rtypes = Seq.empty,
        bodyOpt = Some(stmts)
      )
    }
  }

  sealed trait AssignmentTarget[Ctx <: StatelessContext] extends Typed[Ctx, Type] {
    def ident: Ident
    def isMutable(state: Compiler.State[Ctx]): Boolean = state.getVariable(ident).isMutable
    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]]
  }
  final case class AssignmentSimpleTarget[Ctx <: StatelessContext](ident: Ident)
      extends AssignmentTarget[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Type                 = state.getVariable(ident).tpe
    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = state.genStoreCode(ident)
  }
  final case class AssignmentArrayElementTarget[Ctx <: StatelessContext](
      ident: Ident,
      indexes: Seq[Ast.Expr[Ctx]]
  ) extends AssignmentTarget[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Type =
      state.getArrayElementType(Seq(state.getVariable(ident).tpe), indexes)

    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
      val arrayRef = state.getArrayRef(ident)
      getType(state) match {
        case _: Type.FixedSizeArray => arrayRef.subArray(state, indexes).genStoreCode(state)
        case _                      => arrayRef.genStoreCode(state, indexes)
      }
    }
  }

  final case class ConstantVarDef(ident: Ident, value: Val) extends UniqueDef {
    def name: String = ident.name
  }

  final case class EnumField(ident: Ident, value: Val) extends UniqueDef {
    def name: String = ident.name
  }
  final case class EnumDef(id: TypeId, fields: Seq[EnumField]) extends UniqueDef {
    def name: String = id.name
  }
  object EnumDef {
    def fieldIdent(enumId: TypeId, field: Ident): Ident =
      Ident(s"${enumId.name}.${field.name}")
  }

  final case class EventDef(
      id: TypeId,
      fields: Seq[EventField]
  ) extends UniqueDef {
    def name: String = id.name

    def signature: String = s"event ${id.name}(${fields.map(_.signature).mkString(",")})"

    def getFieldNames(): AVector[String]          = AVector.from(fields.view.map(_.ident.name))
    def getFieldTypeSignatures(): AVector[String] = AVector.from(fields.view.map(_.tpe.signature))
  }

  final case class EmitEvent[Ctx <: StatefulContext](id: TypeId, args: Seq[Expr[Ctx]])
      extends Statement[Ctx] {
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
        throw Compiler.Error(
          s"Array type not supported for event ${quote(s"${state.typeId.name}.${id.name}")}"
        )
      }
      val logOpCode = Compiler.genLogs(args.length)
      eventIndex ++ args.flatMap(_.genCode(state)) :+ logOpCode
    }
  }

  final case class Assign[Ctx <: StatelessContext](
      targets: Seq[AssignmentTarget[Ctx]],
      rhs: Expr[Ctx]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      val leftTypes  = targets.map(_.getType(state))
      val rightTypes = rhs.getType(state)
      if (leftTypes != rightTypes) {
        throw Compiler.Error(s"Assign $rightTypes to $leftTypes")
      }
      targets.foreach { target =>
        if (!target.isMutable(state)) {
          throw Compiler.Error(s"Assign to immutable variable: ${target.ident.name}")
        }
      }
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      rhs.genCode(state) ++ targets.flatMap(_.genStore(state)).reverse.flatten
    }
  }
  final case class FuncCall[Ctx <: StatelessContext](
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends Statement[Ctx]
      with ApproveAssets[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      checkApproveAssets(state)
      val funcInfo = state.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
      state.addInternalCall(id)
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val func       = state.getFunc(id)
      val argsType   = args.flatMap(_.getType(state))
      val returnType = func.getReturnType(argsType)
      genApproveCode(state, func) ++
        args.flatMap(_.genCode(state)) ++
        (if (func.isVariadic) Seq(U256Const(Val.U256(U256.unsafe(args.length)))) else Seq.empty) ++
        func.genCode(argsType) ++
        Seq.fill(Type.flattenTypeLength(returnType))(Pop)
    }
  }
  final case class ContractCall(
      obj: Expr[StatefulContext],
      callId: FuncId,
      approveAssets: Seq[ApproveAsset[StatefulContext]],
      args: Seq[Expr[StatefulContext]]
  ) extends Statement[StatefulContext]
      with ContractCallBase {
    override def check(state: Compiler.State[StatefulContext]): Unit = {
      checkApproveAssets(state)
      _getTypeBase(state)
      ()
    }

    override def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      genContractCall(state, true)
    }
  }

  final case class IfBranchStatement[Ctx <: StatelessContext](
      condition: Expr[Ctx],
      body: Seq[Statement[Ctx]]
  ) extends IfBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = body.flatMap(_.genCode(state))
  }
  final case class ElseBranchStatement[Ctx <: StatelessContext](
      body: Seq[Statement[Ctx]]
  ) extends ElseBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = body.flatMap(_.genCode(state))
  }
  final case class IfElseStatement[Ctx <: StatelessContext](
      ifBranches: Seq[IfBranchStatement[Ctx]],
      elseBranchOpt: Option[ElseBranchStatement[Ctx]]
  ) extends IfElse[Ctx]
      with Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      ifBranches.foreach(_.checkCondition(state))
      ifBranches.foreach(_.body.foreach(_.check(state)))
      elseBranchOpt.foreach(_.body.foreach(_.check(state)))
    }
  }
  final case class While[Ctx <: StatelessContext](condition: Expr[Ctx], body: Seq[Statement[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of conditional expr ${quote(condition)}")
      }
      body.foreach(_.check(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val bodyIR   = body.flatMap(_.genCode(state))
      val condIR   = Statement.getCondIR(condition, state, bodyIR.length + 1)
      val whileLen = condIR.length + bodyIR.length + 1
      if (whileLen > 0xff) {
        // TODO: support long branches
        throw Compiler.Error(s"Too many instructions for if-else branches")
      }
      condIR ++ bodyIR :+ Jump(-whileLen)
    }
  }
  final case class ForLoop[Ctx <: StatelessContext](
      initialize: Statement[Ctx],
      condition: Expr[Ctx],
      update: Statement[Ctx],
      body: Seq[Statement[Ctx]]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      initialize.check(state)
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid condition type: $condition")
      }
      body.foreach(_.check(state))
      update.check(state)
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val initializeIR   = initialize.genCode(state)
      val bodyIR         = body.flatMap(_.genCode(state))
      val updateIR       = update.genCode(state)
      val fullBodyLength = bodyIR.length + updateIR.length + 1
      val condIR         = Statement.getCondIR(condition, state, fullBodyLength)
      val jumpLength     = condIR.length + fullBodyLength
      initializeIR ++ condIR ++ bodyIR ++ updateIR :+ Jump(-jumpLength)
    }
  }
  final case class ReturnStmt[Ctx <: StatelessContext](exprs: Seq[Expr[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      state.checkReturn(exprs.flatMap(_.getType(state)))
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      exprs.flatMap(_.genCode(state)) :+ Return
  }

  trait ContractT[Ctx <: StatelessContext] extends UniqueDef {
    def ident: TypeId
    def templateVars: Seq[Argument]
    def fields: Seq[Argument]
    def funcs: Seq[FuncDef[Ctx]]

    def name: String = ident.name

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

    def checkIfPrivateMethodsUsed(state: Compiler.State[Ctx]): Unit = {
      funcs.foreach { func =>
        if (func.isPrivate && !state.internalCallsReversed.get(func.id).exists(_.nonEmpty)) {
          state.warnUnusedPrivateFunction(ident, func.id)
        }
      }
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      state.checkArguments(fields)
      templateVars.zipWithIndex.foreach { case (temp, index) =>
        state.addTemplateVariable(temp.ident, temp.tpe, index)
      }
      fields.foreach(field =>
        state.addFieldVariable(
          field.ident,
          field.tpe,
          field.isMutable,
          field.isUnused,
          isGenerated = false
        )
      )
    }

    def getMethods(state: Compiler.State[Ctx]): AVector[Method[Ctx]] = {
      val methods = AVector.from(funcs.view.map(_.toMethod(state)))
      state.checkUnusedFields()
      methods
    }

    def genCode(state: Compiler.State[Ctx]): VmContract[Ctx]
  }

  final case class AssetScript(
      ident: TypeId,
      templateVars: Seq[Argument],
      funcs: Seq[FuncDef[StatelessContext]]
  ) extends ContractT[StatelessContext] {
    val fields: Seq[Argument] = Seq.empty

    def builtInContractFuncs(): Seq[Compiler.ContractFunc[StatelessContext]] = Seq.empty

    def genCode(state: Compiler.State[StatelessContext]): StatelessScript = {
      check(state)
      StatelessScript
        .from(getMethods(state))
        .getOrElse(throw Compiler.Error(s"No methods found in ${quote(ident.name)}"))
    }
  }

  sealed trait ContractWithState extends ContractT[StatefulContext] {
    def inheritances: Seq[Inheritance]

    def templateVars: Seq[Argument]
    def fields: Seq[Argument]
    def events: Seq[EventDef]
    def constantVars: Seq[ConstantVarDef]
    def enums: Seq[EnumDef]

    def builtInContractFuncs(): Seq[Compiler.ContractFunc[StatefulContext]] = Seq.empty

    def eventsInfo(): Seq[Compiler.EventInfo] = {
      UniqueDef.checkDuplicates(events, "events")
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

    def error(tpe: String): Compiler.Error =
      new Compiler.Error(s"TxScript ${ident.name} should not contain any $tpe")
    def constantVars: Seq[ConstantVarDef] = throw error("constant variable")
    def enums: Seq[EnumDef]               = throw error("enum")
    def getTemplateVarsSignature(): String =
      s"TxScript ${name}(${templateVars.map(_.signature).mkString(",")})"
    def getTemplateVarsNames(): AVector[String] = AVector.from(templateVars.view.map(_.ident.name))
    def getTemplateVarsTypes(): AVector[String] =
      AVector.from(templateVars.view.map(_.tpe.signature))
    def getTemplateVarsMutability(): AVector[Boolean] =
      AVector.from(templateVars.view.map(_.isMutable))

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def genCode(state: Compiler.State[StatefulContext]): StatefulScript = {
      check(state)
      val methods = getMethods(state)
      val script = StatefulScript
        .from(methods)
        .getOrElse(
          throw Compiler.Error(
            "Expected the 1st function to be public and the other functions to be private for tx script"
          )
        )
      // skip check readonly for main function
      val methodsExceptMain = methods.tail
      val funcsExceptMain   = funcs.tail
      methodsExceptMain.foreachWithIndex { case (method, index) =>
        funcsExceptMain(index).checkReadonly(state, method.instrs)
      }
      script
    }
  }

  sealed trait Inheritance {
    def parentId: TypeId
  }
  final case class ContractInheritance(parentId: TypeId, idents: Seq[Ident]) extends Inheritance
  final case class InterfaceInheritance(parentId: TypeId)                    extends Inheritance
  final case class Contract(
      isAbstract: Boolean,
      ident: TypeId,
      templateVars: Seq[Argument],
      fields: Seq[Argument],
      funcs: Seq[FuncDef[StatefulContext]],
      events: Seq[EventDef],
      constantVars: Seq[ConstantVarDef],
      enums: Seq[EnumDef],
      inheritances: Seq[Inheritance]
  ) extends ContractWithState {
    def getFieldsSignature(): String =
      s"Contract ${name}(${fields.map(_.signature).mkString(",")})"
    def getFieldNames(): AVector[String]       = AVector.from(fields.view.map(_.ident.name))
    def getFieldTypes(): AVector[String]       = AVector.from(fields.view.map(_.tpe.signature))
    def getFieldMutability(): AVector[Boolean] = AVector.from(fields.view.map(_.isMutable))

    private def checkFuncs(): Unit = {
      if (funcs.length < 1) {
        throw Compiler.Error(s"No function found in Contract ${quote(ident.name)}")
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    def getFuncUnsafe(funcId: FuncId): FuncDef[StatefulContext] = funcs.find(_.id == funcId).get

    private def checkConstants(state: Compiler.State[StatefulContext]): Unit = {
      UniqueDef.checkDuplicates(constantVars, "constant variables")
      constantVars.foreach(v =>
        state.addConstantVariable(v.ident, Type.fromVal(v.value.tpe), Seq(v.value.toConstInstr))
      )
      UniqueDef.checkDuplicates(enums, "enums")
      enums.foreach(e =>
        e.fields.foreach(field =>
          state.addConstantVariable(
            EnumDef.fieldIdent(e.id, field.ident),
            Type.fromVal(field.value.tpe),
            Seq(field.value.toConstInstr)
          )
        )
      )
    }

    private def checkInheritances(state: Compiler.State[StatefulContext]): Unit = {
      inheritances.foreach { inheritance =>
        val id   = inheritance.parentId
        val kind = state.getContractInfo(id).kind
        if (!kind.inheritable) {
          throw Compiler.Error(s"$kind ${id.name} can not be inherited")
        }
      }
    }

    override def check(state: Compiler.State[StatefulContext]): Unit = {
      checkFuncs()
      checkConstants(state)
      checkInheritances(state)
      super.check(state)
    }

    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      assume(!isAbstract)
      check(state)
      val methods  = getMethods(state)
      val contract = StatefulContract(Type.flattenTypeLength(fields.map(_.tpe)), methods)
      methods.foreachWithIndex { case (method, index) =>
        funcs(index).checkReadonly(state, method.instrs)
      }
      checkIfPrivateMethodsUsed(state)
      contract
    }

    // the state must have been updated in the check pass
    def buildExternalCallCheckTable(
        state: Compiler.State[StatefulContext]
    ): mutable.Map[FuncId, Boolean] = {
      val externalCallCheckedTable = mutable.Map.empty[FuncId, Boolean]
      funcs.foreach(func => externalCallCheckedTable(func.id) = false)

      // TODO: optimize these two functions
      def updateCheckedRecursivelyForPrivateMethod(checkedPrivateCalleeId: FuncId): Unit = {
        state.internalCallsReversed.get(checkedPrivateCalleeId) match {
          case Some(callers) =>
            callers.foreach { caller =>
              updateCheckedRecursively(getFuncUnsafe(caller))
            }
          case None => ()
        }
      }
      def updateCheckedRecursively(func: FuncDef[StatefulContext]): Unit = {
        if (!externalCallCheckedTable(func.id)) {
          externalCallCheckedTable(func.id) = true
          if (func.isPrivate) { // indirect external call check should be in private methods
            updateCheckedRecursivelyForPrivateMethod(func.id)
          }
        }
      }

      funcs.foreach { func =>
        if (func.hasDirectExternalCallCheck()) {
          updateCheckedRecursively(func)
        }
      }
      externalCallCheckedTable
    }
  }

  final case class ContractInterface(
      ident: TypeId,
      funcs: Seq[FuncDef[StatefulContext]],
      events: Seq[EventDef],
      inheritances: Seq[InterfaceInheritance]
  ) extends ContractWithState {
    def error(tpe: String): Compiler.Error =
      new Compiler.Error(s"Interface ${quote(ident.name)} should not contain any ${quote(tpe)}")

    def templateVars: Seq[Argument]       = throw error("template variable")
    def fields: Seq[Argument]             = throw error("field")
    def getFieldsSignature(): String      = throw error("field")
    def getFieldTypes(): Seq[String]      = throw error("field")
    def constantVars: Seq[ConstantVarDef] = throw error("constant variable")
    def enums: Seq[EnumDef]               = throw error("enum")

    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      throw new Compiler.Error(s"Interface ${quote(ident.name)} should not generate code")
    }
  }

  final case class MultiContract(
      contracts: Seq[ContractWithState],
      dependencies: Option[Map[TypeId, Seq[TypeId]]]
  ) {
    lazy val contractsTable = contracts.map { contract =>
      val kind = contract match {
        case _: Ast.ContractInterface =>
          Compiler.ContractKind.Interface
        case _: Ast.TxScript =>
          Compiler.ContractKind.TxScript
        case txContract: Ast.Contract =>
          Compiler.ContractKind.Contract(txContract.isAbstract)
      }
      contract.ident -> Compiler.ContractInfo(kind, contract.funcTable)
    }.toMap

    def get(contractIndex: Int): ContractWithState = {
      if (contractIndex >= 0 && contractIndex < contracts.size) {
        contracts(contractIndex)
      } else {
        throw Compiler.Error(s"Invalid contract index $contractIndex")
      }
    }

    private def getContract(typeId: TypeId): ContractWithState = {
      contracts.find(_.ident == typeId) match {
        case None => throw Compiler.Error(s"Contract ${quote(typeId.name)} does not exist")
        case Some(_: TxScript) =>
          throw Compiler.Error(s"Expected contract ${quote(typeId.name)}, but got script")
        case Some(contract: ContractWithState) => contract
      }
    }

    private def isContract(typeId: TypeId): Boolean = {
      contracts.find(_.ident == typeId) match {
        case None => throw Compiler.Error(s"Contract ${quote(typeId.name)} does not exist")
        case Some(contract: Contract) if !contract.isAbstract => true
        case _                                                => false
      }
    }

    private def getInterface(typeId: TypeId): ContractInterface = {
      getContract(typeId) match {
        case interface: ContractInterface => interface
        case _ => throw Compiler.Error(s"Interface ${typeId.name} does not exist")
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

      val allParents = mutable.LinkedHashMap.empty[TypeId, ContractWithState]
      contract.inheritances.foreach { inheritance =>
        val parentId       = inheritance.parentId
        val parentContract = getContract(parentId)
        MultiContract.checkInheritanceFields(contract, inheritance, parentContract)

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
    def extendedContracts(): MultiContract = {
      UniqueDef.checkDuplicates(contracts, "TxScript/Contract/Interface")

      val parentsCache = buildDependencies()
      val newContracts: Seq[ContractWithState] = contracts.map {
        case script: TxScript =>
          script
        case c: Contract =>
          val (funcs, events, constantVars, enums) = MultiContract.extractDefs(parentsCache, c)
          Contract(
            c.isAbstract,
            c.ident,
            c.templateVars,
            c.fields,
            funcs,
            events,
            constantVars,
            enums,
            c.inheritances
          )
        case i: ContractInterface =>
          val (funcs, events, _, _) = MultiContract.extractDefs(parentsCache, i)
          ContractInterface(i.ident, funcs, events, i.inheritances)
      }
      val dependencies = Map.from(parentsCache.map(p => (p._1, p._2.map(_.ident))))
      MultiContract(newContracts, Some(dependencies))
    }

    def genStatefulScripts()(implicit
        compilerOptions: CompilerOptions
    ): AVector[(StatefulScript, TxScript, AVector[String])] = {
      AVector.from(contracts.view.zipWithIndex.collect { case (_: TxScript, index) =>
        genStatefulScript(index)
      })
    }

    def genStatefulScript(
        contractIndex: Int
    )(implicit compilerOptions: CompilerOptions): (StatefulScript, TxScript, AVector[String]) = {
      val state = Compiler.State.buildFor(this, contractIndex)
      get(contractIndex) match {
        case script: TxScript => (script.genCode(state), script, state.getWarnings)
        case _: Contract      => throw Compiler.Error(s"The code is for Contract, not for TxScript")
        case _: ContractInterface =>
          throw Compiler.Error(s"The code is for Interface, not for TxScript")
      }
    }

    private[vm] def checkExternalCallPermissions(
        contractState: Compiler.State[StatefulContext],
        contract: Contract,
        externalCallCheckTables: mutable.Map[TypeId, mutable.Map[FuncId, Boolean]]
    ): Unit = {
      val allNoExternalCallChecks: mutable.Set[(TypeId, FuncId)] = mutable.Set.empty
      contract.funcs.foreach { func =>
        // To check that external calls should have external call checks
        contractState.externalCalls.get(func.id) match {
          case Some(callees) if callees.nonEmpty =>
            callees.foreach { case funcRef @ (typeId, funcId) =>
              if (!externalCallCheckTables(typeId)(funcId)) {
                allNoExternalCallChecks.addOne(funcRef)
              }
            }
          case _ => ()
        }
      }
      allNoExternalCallChecks.foreach { case (typeId, funcId) =>
        contractState.warnExternalCallCheck(typeId, funcId)
      }
    }

    def checkInterfaceExternalCallCheck(
        interfaceTypeId: TypeId,
        externalCallCheckTables: mutable.Map[TypeId, mutable.Map[FuncId, Boolean]]
    ): Unit = {
      assume(dependencies.isDefined)
      val children = dependencies
        .map(_.filter { case (child, parents) =>
          parents.contains(interfaceTypeId) && isContract(child)
        }.keys.toSeq)
        .getOrElse(Seq.empty)
      val interface = getInterface(interfaceTypeId)
      children.foreach { case contractId =>
        val table = externalCallCheckTables(contractId)
        interface.funcs.foreach { func =>
          if (func.useExternalCallCheck && !table(func.id)) {
            throw Compiler.Error(Warnings.noExternalCallCheckMsg(contractId.name, func.id.name))
          }
        }
      }
    }

    def genStatefulContracts()(implicit
        compilerOptions: CompilerOptions
    ): AVector[(StatefulContract, Contract, AVector[String], Int)] = {
      val states = AVector.tabulate(contracts.length)(Compiler.State.buildFor(this, _))
      val externalCallCheckTables = mutable.Map.empty[TypeId, mutable.Map[FuncId, Boolean]]
      val statefulContracts = AVector.from(contracts.view.zipWithIndex.collect {
        case (contract: Contract, index) if !contract.isAbstract =>
          val state            = states(index)
          val statefulContract = contract.genCode(state)
          val table            = contract.buildExternalCallCheckTable(state)
          externalCallCheckTables.update(contract.ident, table)
          (statefulContract, contract, state, index)
      })
      contracts.foreach {
        case interface: ContractInterface =>
          checkInterfaceExternalCallCheck(interface.ident, externalCallCheckTables)
          val table = mutable.Map.from(interface.funcs.map(_.id -> true))
          externalCallCheckTables.update(interface.ident, table)
        case _ => ()
      }
      statefulContracts.map { case (statefulContract, contract, state, index) =>
        checkExternalCallPermissions(state, contract, externalCallCheckTables)
        (statefulContract, contract, state.getWarnings, index)
      }
    }

    def genStatefulContract(
        contractIndex: Int
    )(implicit compilerOptions: CompilerOptions): (StatefulContract, Contract, AVector[String]) = {
      get(contractIndex) match {
        case contract: Contract =>
          if (contract.isAbstract) {
            throw Compiler.Error(
              s"Code generation is not supported for abstract contract ${quote(contract.ident.name)}"
            )
          }
          val statefulContracts = genStatefulContracts()
          statefulContracts.find(_._4 == contractIndex) match {
            case Some(v) => (v._1, v._2, v._3)
            case None => // should never happen
              throw Compiler.Error(s"Failed to compile contract ${contract.ident.name}")
          }
        case _: TxScript => throw Compiler.Error(s"The code is for TxScript, not for Contract")
        case _: ContractInterface =>
          throw Compiler.Error(s"The code is for Interface, not for Contract")
      }
    }
  }

  object MultiContract {
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
            throw Compiler.Error(
              s"Inherited field ${quote(ident.name)} does not exist in contract ${quote(contract.name)}"
            )
          )
      }
      if (fields != parentContract.fields) {
        throw Compiler.Error(
          s"Invalid contract inheritance fields, expected ${quote(parentContract.fields)}, got ${quote(fields)}"
        )
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def extractDefs(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        contract: ContractWithState
    ): (Seq[FuncDef[StatefulContext]], Seq[EventDef], Seq[ConstantVarDef], Seq[EnumDef]) = {
      val parents = parentsCache(contract.ident)
      val (allContracts, _allInterfaces) =
        (parents :+ contract).partition(_.isInstanceOf[Contract])
      val allInterfaces =
        sortInterfaces(parentsCache, _allInterfaces.map(_.asInstanceOf[ContractInterface]))

      val allFuncs                             = (allInterfaces ++ allContracts).flatMap(_.funcs)
      val (abstractFuncs, nonAbstractFuncs)    = allFuncs.partition(_.bodyOpt.isEmpty)
      val (unimplementedFuncs, allUniqueFuncs) = checkFuncs(abstractFuncs, nonAbstractFuncs)
      val constantVars                         = allContracts.flatMap(_.constantVars)
      val enums                                = allContracts.flatMap(_.enums)

      val contractEvents = allContracts.flatMap(_.events)
      val events         = allInterfaces.flatMap(_.events) ++ contractEvents

      val resultFuncs = contract match {
        case _: TxScript =>
          throw Compiler.Error("Extract definitions from TxScript is unexpected")
        case txContract: Contract =>
          if (!txContract.isAbstract && unimplementedFuncs.nonEmpty) {
            val methodNames = unimplementedFuncs.map(_.name).mkString(",")
            throw Compiler.Error(
              s"Contract ${txContract.name} has unimplemented methods: $methodNames"
            )
          }

          allUniqueFuncs
        case interface: ContractInterface =>
          if (nonAbstractFuncs.nonEmpty) {
            val methodNames = nonAbstractFuncs.map(_.name).mkString(",")
            throw Compiler.Error(
              s"Interface ${interface.name} has implemented methods: $methodNames"
            )
          }
          unimplementedFuncs
      }

      (resultFuncs, events, constantVars, enums)
    }

    private def sortInterfaces(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        allInterfaces: Seq[ContractInterface]
    ): Seq[ContractInterface] = {
      allInterfaces.sortBy(interface => parentsCache(interface.ident).length)
    }

    def checkFuncs(
        abstractFuncs: Seq[FuncDef[StatefulContext]],
        nonAbstractFuncs: Seq[FuncDef[StatefulContext]]
    ): (Seq[FuncDef[StatefulContext]], Seq[FuncDef[StatefulContext]]) = {
      val nonAbstractFuncSet = nonAbstractFuncs.view.map(f => f.id.name -> f).toMap
      val abstractFuncsSet   = abstractFuncs.view.map(f => f.id.name -> f).toMap
      if (nonAbstractFuncSet.size != nonAbstractFuncs.size) {
        val duplicates = UniqueDef.duplicates(nonAbstractFuncs)
        throw Compiler.Error(s"These functions are implemented multiple times: $duplicates")
      }

      if (abstractFuncsSet.size != abstractFuncs.size) {
        val duplicates = UniqueDef.duplicates(abstractFuncs)
        throw Compiler.Error(s"These abstract functions are defined multiple times: $duplicates")
      }

      val (implementedFuncs, unimplementedFuncs) =
        abstractFuncs.partition(func => nonAbstractFuncSet.contains(func.id.name))

      implementedFuncs.foreach { abstractFunc =>
        val funcName                = abstractFunc.id.name
        val implementedAbstractFunc = nonAbstractFuncSet(funcName)
        if (implementedAbstractFunc.copy(bodyOpt = None) != abstractFunc) {
          throw new Compiler.Error(
            s"Function ${quote(funcName)} is implemented with wrong signature"
          )
        }
      }

      val inherited    = abstractFuncs.map { f => nonAbstractFuncSet.getOrElse(f.id.name, f) }
      val nonInherited = nonAbstractFuncs.filter(f => !abstractFuncsSet.contains(f.id.name))
      (unimplementedFuncs, inherited ++ nonInherited)
    }
  }
}
// scalastyle:on number.of.methods number.of.types
