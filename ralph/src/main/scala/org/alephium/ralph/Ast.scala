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

package org.alephium.ralph

import java.nio.charset.StandardCharsets

import scala.annotation.tailrec
import scala.collection.mutable

import akka.util.ByteString

import org.alephium.protocol.vm
import org.alephium.protocol.vm.{ALPHTokenId => ALPHTokenIdInstr, Contract => VmContract, _}
import org.alephium.ralph.LogicalOperator.Not
import org.alephium.ralph.Parser.UsingAnnotation
import org.alephium.util.{AVector, Hex, I256, U256}

// scalastyle:off number.of.methods number.of.types file.size.limit
object Ast {
  type StdInterfaceId = Val.ByteVec
  val StdInterfaceIdPrefix: ByteString = ByteString("ALPH", StandardCharsets.UTF_8)
  private val stdArg: Argument =
    Argument(Ident("__stdInterfaceId"), Type.ByteVec, isMutable = false, isUnused = true)

  trait Positioned {
    var sourceIndex: Option[SourceIndex] = None

    def atSourceIndex(fromIndex: Int, endIndex: Int, fileURI: Option[java.net.URI]): this.type = {
      require(this.sourceIndex.isEmpty)
      this.sourceIndex = Some(SourceIndex(fromIndex, endIndex - fromIndex, fileURI))
      this
    }
    def atSourceIndex(sourceIndex: Option[SourceIndex]): this.type = {
      require(this.sourceIndex.isEmpty)
      this.sourceIndex = sourceIndex
      this
    }
    def overwriteSourceIndex(
        fromIndex: Int,
        endIndex: Int,
        fileURI: Option[java.net.URI]
    ): this.type = {
      require(this.sourceIndex.isDefined)
      this.sourceIndex = Some(SourceIndex(fromIndex, endIndex - fromIndex, fileURI))
      this
    }

    /*
     * This function update a `CompilerError` when the source index was not
     * available at the time of the error.
     * For example for `Operator` or `BuiltIn`, we could add the `SourceIndex`
     * to the `getReturnType` function, but it implies a lot of changes in the
     * all `ralph` module, while the position is not useful along the way.
     */
    def positionedError[T](f: => T): T = {
      try {
        f
      } catch {
        case e: error.CompilerError.Default =>
          if (sourceIndex.isDefined && e.sourceIndex.isEmpty) {
            throw e.copy(sourceIndex = sourceIndex)
          } else {
            throw e
          }
      }
    }
  }

  final case class Ident(name: String)                      extends Positioned
  final case class TypeId(name: String)                     extends Positioned
  final case class FuncId(name: String, isBuiltIn: Boolean) extends Positioned

  final case class Argument(ident: Ident, tpe: Type, isMutable: Boolean, isUnused: Boolean)
      extends Positioned {
    def signature: String = {
      val prefix = if (isMutable) "mut " else ""
      s"${prefix}${ident.name}:${tpe.signature}"
    }
  }

  final case class EventField(ident: Ident, tpe: Type) extends Positioned {
    def signature: String = s"${ident.name}:${tpe.signature}"
  }

  final case class AnnotationField(ident: Ident, value: Val)           extends Positioned
  final case class Annotation(id: Ident, fields: Seq[AnnotationField]) extends Positioned

  object FuncId {
    lazy val empty: FuncId = FuncId("", isBuiltIn = false)
  }

  def funcName(typeId: TypeId, funcId: FuncId): String = quote(s"${typeId.name}.${funcId.name}")

  final case class ApproveAsset[Ctx <: StatelessContext](
      address: Expr[Ctx],
      tokenAmounts: Seq[(Expr[Ctx], Expr[Ctx])]
  ) extends Positioned {
    def check(state: Compiler.State[Ctx]): Unit = {
      if (address.getType(state) != Seq(Type.Address)) {
        throw Compiler.Error(s"Invalid address type: ${address}", address.sourceIndex)
      }
      tokenAmounts
        .find(p =>
          (p._1.getType(state), p._2.getType(state)) != (Seq(Type.ByteVec), Seq(Type.U256))
        ) match {
        case None => ()
        case Some((exp, _)) =>
          throw Compiler.Error(s"Invalid token amount type: ${tokenAmounts}", exp.sourceIndex)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val approveCount = tokenAmounts.length
      assume(approveCount >= 1)
      val approveTokens: Seq[Instr[Ctx]] = tokenAmounts.flatMap {
        case (ALPHTokenId(), amount) =>
          amount.genCode(state) :+ ApproveAlph.asInstanceOf[Instr[Ctx]]
        case (tokenId, amount) =>
          tokenId.genCode(state) ++ amount.genCode(state) :+ ApproveToken.asInstanceOf[Instr[Ctx]]
      }
      address.genCode(state) ++ Seq.fill(approveCount - 1)(Dup) ++ approveTokens
    }
  }

  trait ApproveAssets[Ctx <: StatelessContext] extends Positioned {
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
          throw Compiler.Error(
            s"Function `${func.name}` does not use preapproved assets",
            sourceIndex
          )
        case (false, true) =>
          throw Compiler.Error(
            s"Function `${func.name}` needs preapproved assets, please use braces syntax",
            sourceIndex
          )
        case _ => ()
      }
      approveAssets.flatMap(_.genCode(state))
    }
  }

  trait Typed[Ctx <: StatelessContext, T] extends Positioned {
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

  final case class ALPHTokenId[Ctx <: StatelessContext]() extends Expr[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(Type.ByteVec)

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = Seq(
      ALPHTokenIdInstr.asInstanceOf[Instr[Ctx]]
    )
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
        throw Compiler.Error(
          s"Expected single type for array element, got ${quote(elements)}",
          sourceIndex
        )
      }
      if (elements.drop(0).exists(_.getType(state) != baseType)) {
        throw Compiler.Error(
          s"Array elements should have same type, got ${quote(elements)}",
          sourceIndex
        )
      }
      Seq(Type.FixedSizeArray(baseType(0), elements.size))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      elements.flatMap(_.genCode(state))
    }
  }
  final case class ArrayElement[Ctx <: StatelessContext](
      array: Expr[Ctx],
      index: Ast.Expr[Ctx]
  ) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      Seq(state.getArrayElementType(array, index))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val (arrayRef, codes) = state.getOrCreateArrayRef(array)
      codes ++ arrayRef.genLoadCode(state, index)
    }
  }
  final case class Variable[Ctx <: StatelessContext](id: Ident) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(state.resolveType(id))

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
      positionedError(op.getReturnType(expr.getType(state)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      expr.genCode(state) ++ op.genCode(expr.getType(state))
    }
  }
  final case class Binop[Ctx <: StatelessContext](op: Operator, left: Expr[Ctx], right: Expr[Ctx])
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      positionedError(op.getReturnType(left.getType(state) ++ right.getType(state)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      positionedError(
        left.genCode(state) ++ right.genCode(state) ++ op.genCode(
          left.getType(state) ++ right.getType(state)
        )
      )
    }
  }
  final case class ContractConv[Ctx <: StatelessContext](contractType: TypeId, address: Expr[Ctx])
      extends Expr[Ctx] {
    override protected def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      state.checkContractType(contractType)

      if (address.getType(state) != Seq(Type.ByteVec)) {
        throw Compiler.Error(s"Invalid expr $address for contract address", address.sourceIndex)
      }

      val contractInfo = state.getContractInfo(contractType)
      if (!contractInfo.kind.instantiable) {
        throw Compiler.Error(s"${contractType.name} is not instantiable", sourceIndex)
      }

      Seq(Type.Contract(contractType))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      address.genCode(state)
  }

  sealed trait CallAst[Ctx <: StatelessContext] extends ApproveAssets[Ctx] {
    def id: FuncId
    def args: Seq[Expr[Ctx]]
    def ignoreReturn: Boolean

    def getFunc(state: Compiler.State[Ctx]): Compiler.FuncInfo[Ctx]

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def _genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      (id, args) match {
        case (BuiltIn.approveToken.funcId, Seq(from, ALPHTokenId(), amount)) =>
          Seq(from, amount).flatMap(_.genCode(state)) :+ ApproveAlph.asInstanceOf[Instr[Ctx]]
        case (BuiltIn.tokenRemaining.funcId, Seq(from, ALPHTokenId())) =>
          val instrs = from.genCode(state) :+ AlphRemaining.asInstanceOf[Instr[Ctx]]
          if (ignoreReturn) instrs :+ Pop.asInstanceOf[Instr[Ctx]] else instrs
        case (BuiltIn.transferToken.funcId, Seq(from, to, ALPHTokenId(), amount)) =>
          Seq(from, to, amount).flatMap(_.genCode(state)) :+ TransferAlph.asInstanceOf[Instr[Ctx]]
        case (BuiltIn.transferTokenFromSelf.funcId, Seq(to, ALPHTokenId(), amount)) =>
          Seq(to, amount).flatMap(_.genCode(state)) :+ TransferAlphFromSelf.asInstanceOf[Instr[Ctx]]
        case (BuiltIn.transferTokenToSelf.funcId, Seq(from, ALPHTokenId(), amount)) =>
          Seq(from, amount).flatMap(_.genCode(state)) :+ TransferAlphToSelf.asInstanceOf[Instr[Ctx]]
        case _ =>
          val func     = getFunc(state)
          val argsType = args.flatMap(_.getType(state))
          val variadicInstrs = if (func.isVariadic) {
            Seq(U256Const(Val.U256.unsafe(args.length)))
          } else {
            Seq.empty
          }
          val instrs = genApproveCode(state, func) ++
            func.genCodeForArgs(args, state) ++
            variadicInstrs ++
            func.genCode(argsType)
          if (ignoreReturn) {
            val returnType = positionedError(func.getReturnType(argsType, state))
            instrs ++ Seq.fill(state.flattenTypeLength(returnType))(Pop)
          } else {
            instrs
          }
      }
    }

    @inline final def checkStaticContractFunction(
        typeId: TypeId,
        funcId: FuncId,
        func: Compiler.ContractFunc[Ctx]
    ): Unit = {
      if (!func.isStatic) {
        throw Compiler.Error(
          s"Expected static function, got ${funcName(typeId, funcId)}",
          funcId.sourceIndex
        )
      }
    }
  }

  final case class CallExpr[Ctx <: StatelessContext](
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends Expr[Ctx]
      with CallAst[Ctx] {
    def ignoreReturn: Boolean = false

    def getFunc(state: Compiler.State[Ctx]): Compiler.FuncInfo[Ctx] = state.getFunc(id)

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      checkApproveAssets(state)
      val funcInfo = state.getFunc(id)
      positionedError(funcInfo.getReturnType(args.flatMap(_.getType(state)), state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.addInternalCall(
        id
      ) // don't put this in _getType, otherwise the statement might get skipped
      _genCode(state)
    }
  }

  final case class ContractStaticCallExpr[Ctx <: StatelessContext](
      contractId: TypeId,
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends Expr[Ctx]
      with CallAst[Ctx] {
    def ignoreReturn: Boolean = false

    def getFunc(state: Compiler.State[Ctx]): Compiler.ContractFunc[Ctx] =
      state.getFunc(contractId, id)

    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      checkApproveAssets(state)
      val funcInfo = getFunc(state)
      checkStaticContractFunction(contractId, id, funcInfo)
      positionedError(funcInfo.getReturnType(args.flatMap(_.getType(state)), state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      _genCode(state)
    }
  }

  trait ContractCallBase extends ApproveAssets[StatefulContext] {
    def obj: Expr[StatefulContext]
    def callId: FuncId
    def args: Seq[Expr[StatefulContext]]

    @inline def getContractType(state: Compiler.State[StatefulContext]): Type.Contract = {
      val objType = obj.getType(state)
      if (objType.length != 1) {
        throw Compiler.Error(
          s"Expected a single parameter for contract object, got ${quote(obj)}",
          obj.sourceIndex
        )
      } else {
        state.resolveType(objType(0)) match {
          case contract: Type.Contract => contract
          case _ =>
            throw Compiler.Error(
              s"Expected a contract for ${quote(callId)}, got ${quote(obj)}",
              obj.sourceIndex
            )
        }
      }
    }

    def _getTypeBase(state: Compiler.State[StatefulContext]): (TypeId, Seq[Type]) = {
      val contractType = getContractType(state)
      val contractInfo = state.getContractInfo(contractType.id)
      if (contractInfo.kind == Compiler.ContractKind.Interface) {
        state.addInterfaceFuncCall(state.currentScope)
      }
      val funcInfo = state.getFunc(contractType.id, callId)
      checkNonStaticContractFunction(contractType.id, callId, funcInfo)
      state.addExternalCall(contractType.id, callId)
      val retTypes = positionedError(funcInfo.getReturnType(args.flatMap(_.getType(state)), state))
      (contractType.id, retTypes)
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genContractCall(
        state: Compiler.State[StatefulContext],
        popReturnValues: Boolean
    ): Seq[Instr[StatefulContext]] = {
      val contract  = obj.getType(state)(0).asInstanceOf[Type.Contract]
      val func      = state.getFunc(contract.id, callId)
      val argLength = state.flattenTypeLength(func.argsType)
      val argTypes  = args.flatMap(_.getType(state))
      val retTypes  = func.getReturnType(argTypes, state)
      val retLength = state.flattenTypeLength(retTypes)
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

    @inline final def checkNonStaticContractFunction(
        typeId: TypeId,
        funcId: FuncId,
        func: Compiler.ContractFunc[StatefulContext]
    ): Unit = {
      if (func.isStatic) {
        // TODO: use `obj.funcId` instead of `typeId.funcId`
        throw Compiler.Error(
          s"Expected non-static function, got ${funcName(typeId, funcId)}",
          funcId.sourceIndex
        )
      }
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
      _getTypeBase(state)._2
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

  trait IfBranch[Ctx <: StatelessContext] extends Positioned {
    def condition: Expr[Ctx]
    def checkCondition(state: Compiler.State[Ctx]): Unit = {
      val conditionType = condition.getType(state)
      if (conditionType != Seq(Type.Bool)) {
        throw Compiler.Error(
          s"Invalid type of condition expr: $conditionType",
          condition.sourceIndex
        )
      }
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  trait ElseBranch[Ctx <: StatelessContext] extends Positioned {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  trait IfElse[Ctx <: StatelessContext] extends Positioned {
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
            s"Invalid types of if-else expression branches, expected ${quote(elseBranchType)}, got ${quote(ifBranchType)}",
            sourceIndex
          )
        }
      }
      elseBranchType
    }
  }

  final case class StringLiteral[Ctx <: StatelessContext](
      string: Val.ByteVec
  ) extends Expr[Ctx]
      with Positioned {
    def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(Type.ByteVec)

    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      Seq(BytesConst(string))
    }
  }

  final case class StructField(ident: Ident, isMutable: Boolean, tpe: Type) extends UniqueDef {
    def name: String      = ident.name
    def signature: String = s"${ident.name}:${tpe.signature}"
  }

  sealed trait Entity
  final case class Struct(id: TypeId, fields: Seq[StructField]) extends UniqueDef with Entity {
    lazy val tpe: Type.Struct = Type.Struct(id)

    def name: String = id.name

    def getFieldNames(): AVector[String]          = AVector.from(fields.view.map(_.ident.name))
    def getFieldTypeSignatures(): AVector[String] = AVector.from(fields.view.map(_.tpe.signature))
    def getFieldsMutability(): AVector[Boolean]   = AVector.from(fields.view.map(_.isMutable))

    def getField(selector: Ident): StructField = {
      fields
        .find(_.ident == selector)
        .getOrElse(
          throw Compiler.Error(
            s"Field ${selector.name} does not exist in struct ${id.name}",
            selector.sourceIndex
          )
        )
    }

    def calcFieldOffset[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        selector: Ast.Ident,
        isMutable: Boolean
    ): (Int, Int) = {
      val result = fields.slice(0, fields.indexWhere(_.ident == selector)).flatMap { field =>
        val isFieldMutable = isMutable && field.isMutable
        state.flattenTypeMutability(field.tpe, isFieldMutable)
      }
      val mutFieldSize = result.count(identity)
      (result.length - mutFieldSize, mutFieldSize)
    }

    def calcLocalOffset[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        selector: Ast.Ident
    ): Int = {
      val types = fields.slice(0, fields.indexWhere(_.ident == selector)).map(_.tpe)
      state.flattenTypeLength(types)
    }
  }

  final case class StructCtor[Ctx <: StatelessContext](id: TypeId, fields: Seq[(Ident, Expr[Ctx])])
      extends Expr[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      val struct   = state.getStruct(id)
      val expected = struct.fields.map(field => (field.ident, Seq(state.resolveType(field.tpe))))
      val have     = fields.map { case (ident, expr) => (ident, expr.getType(state)) }
      if (expected.length != have.length || have.exists(f => !expected.contains(f))) {
        throw Compiler.Error(
          s"Invalid struct fields, expect ${struct.fields.map(_.signature)}",
          id.sourceIndex
        )
      }
      Seq(struct.tpe)
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val struct = state.getStruct(id)
      val sortedFields = struct.fields.map { field =>
        fields
          .find(_._1 == field.ident)
          .getOrElse(
            throw Compiler.Error(s"Struct field ${field.ident} does not exist", id.sourceIndex)
          )
      }
      sortedFields.flatMap(_._2.genCode(state))
    }
  }

  final case class StructFieldSelector[Ctx <: StatelessContext](expr: Expr[Ctx], selector: Ident)
      extends Expr[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      Seq(state.getStructFieldType(expr, selector))
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val (structRef, codes) = state.getOrCreateStructRef(expr)
      codes ++ structRef.genLoadCode(state, selector)
    }
  }

  sealed trait Statement[Ctx <: StatelessContext] extends Positioned {
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

  sealed trait VarDeclaration                               extends Positioned
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
          s"Invalid variable declaration, expected ${types.length} variables, got ${vars.length} variables",
          sourceIndex
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
        case (AnonymousVar, tpe) =>
          Seq(Seq.fill(state.flattenTypeLength(Seq(tpe)))(Pop))
      }
      value.genCode(state) ++ storeCodes.reverse.flatten
    }
  }

  trait UniqueDef extends Positioned {
    def name: String
  }

  object UniqueDef {
    def checkDuplicates(defs: Seq[UniqueDef], name: String): Unit = {
      if (defs.distinctBy(_.name).size != defs.size) {
        val (dups, sourceIndex) = duplicates(defs)
        throw Compiler.Error(
          s"These $name are defined multiple times: ${dups}",
          sourceIndex
        )
      }
    }

    def duplicates(defs: Seq[UniqueDef]): (String, Option[SourceIndex]) = {
      val dups = defs
        .groupBy(_.name)
        .filter(_._2.size > 1)
      val sourceIndex = dups.values.headOption.flatMap(_.drop(1).headOption.flatMap(_.sourceIndex))

      (dups.keys.mkString(", "), sourceIndex)
    }
  }

  final case class FuncSignature(
      id: FuncId,
      isPublic: Boolean,
      usePreapprovedAssets: Boolean,
      args: Seq[(Type, Boolean)],
      rtypes: Seq[Type]
  )

  final case class FuncDef[Ctx <: StatelessContext](
      annotations: Seq[Annotation],
      id: FuncId,
      isPublic: Boolean,
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      useCheckExternalCaller: Boolean,
      useUpdateFields: Boolean,
      args: Seq[Argument],
      rtypes: Seq[Type],
      bodyOpt: Option[Seq[Statement[Ctx]]]
  ) extends UniqueDef {
    def name: String              = id.name
    def isPrivate: Boolean        = !isPublic
    val body: Seq[Statement[Ctx]] = bodyOpt.getOrElse(Seq.empty)

    private var funcAccessedVarsCache: Option[Set[Compiler.AccessVariable]] = None

    def hasCheckExternalCallerAnnotation: Boolean = {
      annotations.find(_.id.name == UsingAnnotation.id) match {
        case Some(usingAnnotation) =>
          usingAnnotation.fields.exists(_.ident.name == UsingAnnotation.useCheckExternalCallerKey)
        case None => false
      }
    }

    def isSimpleViewFunc(state: Compiler.State[Ctx]): Boolean = {
      val hasInterfaceFuncCall = state.hasInterfaceFuncCallSet.contains(id)
      val hasMigrateSimple = body.exists {
        case FuncCall(id, _, _) => id.isBuiltIn && id.name == "migrate"
        case _                  => false
      }
      !(useUpdateFields || usePreapprovedAssets || useAssetsInContract || hasInterfaceFuncCall || hasMigrateSimple)
    }

    def signature: FuncSignature = FuncSignature(
      id,
      isPublic,
      usePreapprovedAssets,
      args.map(arg => (arg.tpe, arg.isMutable)),
      rtypes
    )
    def getArgNames(): AVector[String]          = AVector.from(args.view.map(_.ident.name))
    def getArgTypeSignatures(): AVector[String] = AVector.from(args.view.map(_.tpe.signature))
    def getArgMutability(): AVector[Boolean]    = AVector.from(args.view.map(_.isMutable))
    def getReturnSignatures(): AVector[String]  = AVector.from(rtypes.view.map(_.signature))

    def hasDirectCheckExternalCaller(): Boolean = {
      !useCheckExternalCaller || // check external caller manually disabled
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
        case Some(call: FuncCall[_]) if call.id.name == "panic" && call.id.isBuiltIn == true => ()
        case _ =>
          throw Compiler.Error(
            s"Expected return statement for function ${quote(id.name)}",
            id.sourceIndex
          )
      }
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      state.setFuncScope(id)
      state.checkArguments(args)
      args.foreach { arg =>
        val argTpe = state.resolveType(arg.tpe)
        state.addLocalVariable(arg.ident, argTpe, arg.isMutable, arg.isUnused, isGenerated = false)
      }
      funcAccessedVarsCache match {
        case Some(vars) => // the function has been compiled before
          state.addAccessedVars(vars)
          body.foreach(_.check(state))
        case None =>
          body.foreach(_.check(state))
          val currentScopeUsedVars = Set.from(state.currentScopeAccessedVars)
          funcAccessedVarsCache = Some(currentScopeUsedVars)
          state.addAccessedVars(currentScopeUsedVars)
      }
      state.checkUnusedLocalVars(id)
      state.checkUnassignedLocalMutableVars(id)
      if (rtypes.nonEmpty) checkRetTypes(body.lastOption)
    }

    def genMethod(state: Compiler.State[Ctx]): Method[Ctx] = {
      state.setFuncScope(id)
      val instrs    = body.flatMap(_.genCode(state))
      val localVars = state.getLocalVars(id)

      Method[Ctx](
        isPublic,
        usePreapprovedAssets,
        useAssetsInContract,
        argsLength = state.flattenTypeLength(args.map(_.tpe)),
        localsLength = localVars.length,
        returnLength = state.flattenTypeLength(rtypes),
        AVector.from(instrs)
      )
    }
  }

  object FuncDef {
    def main(
        stmts: Seq[Ast.Statement[StatefulContext]],
        usePreapprovedAssets: Boolean,
        useAssetsInContract: Boolean,
        useUpdateFields: Boolean
    ): FuncDef[StatefulContext] = {
      FuncDef[StatefulContext](
        Seq.empty,
        id = FuncId("main", false),
        isPublic = true,
        usePreapprovedAssets = usePreapprovedAssets,
        useAssetsInContract = useAssetsInContract,
        useCheckExternalCaller = true,
        useUpdateFields = useUpdateFields,
        args = Seq.empty,
        rtypes = Seq.empty,
        bodyOpt = Some(stmts)
      )
    }
  }

  sealed trait AssignmentTarget[Ctx <: StatelessContext] extends Typed[Ctx, Type] {
    def ident: Ident

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    protected def isTypeMutable(tpe: Type, state: Compiler.State[Ctx]): Boolean = {
      state.resolveType(tpe) match {
        case t: Type.Struct =>
          val struct = state.getStruct(t.id)
          struct.fields.forall(field => field.isMutable && isTypeMutable(field.tpe, state))
        case t: Type.FixedSizeArray => isTypeMutable(t.baseType, state)
        case _                      => true
      }
    }
    protected def checkStructField(
        state: Compiler.State[Ctx],
        structRef: StructRef[Ctx],
        selector: Ident,
        checkType: Boolean,
        sourceIndex: Option[SourceIndex]
    ): Unit = {
      val field = structRef.ast.getField(selector)
      if (!field.isMutable) {
        throw Compiler.Error(
          s"Cannot assign to immutable field ${field.name} in struct ${structRef.tpe.id.name}.",
          sourceIndex
        )
      }
      if (checkType && !isTypeMutable(field.tpe, state)) {
        throw Compiler.Error(
          s"Cannot assign to field ${field.name} in struct ${structRef.tpe.id.name}." +
            s" Assignment only works when all of the field selectors are mutable.",
          sourceIndex
        )
      }
      if (!structRef.isMutable) {
        throw Compiler.Error(
          s"Cannot assign to immutable struct ${structRef.tpe.id.name}. Assignment only works when all of the field selectors are mutable.",
          sourceIndex
        )
      }
    }
    def getStructRef(ref: VariablesRef[Ctx], sourceIndex: Option[SourceIndex]): StructRef[Ctx] = {
      ref match {
        case ref: StructRef[Ctx @unchecked] => ref
        case _ =>
          throw Compiler.Error(s"Expected struct type, got ${ref.tpe}", sourceIndex)
      }
    }
    def getArrayRef(ref: VariablesRef[Ctx], sourceIndex: Option[SourceIndex]): ArrayRef[Ctx] = {
      ref match {
        case ref: ArrayRef[Ctx @unchecked] => ref
        case _ =>
          throw Compiler.Error(s"Expected array type, got ${ref.tpe}", sourceIndex)
      }
    }
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    protected def getRef(
        state: Compiler.State[Ctx],
        expr: Expr[Ctx],
        sourceIndex: Option[SourceIndex]
    ): VariablesRef[Ctx] = {
      expr match {
        case ArrayElement(array @ StructFieldSelector(struct, selector), index) =>
          val structRef = getStructRef(getRef(state, struct, sourceIndex), struct.sourceIndex)
          checkStructField(state, structRef, selector, checkType = false, sourceIndex)
          val arrayRef = getArrayRef(structRef.subRef(state, selector), array.sourceIndex)
          arrayRef.subRef(state, index)
        case ArrayElement(array @ Variable(ident), index) =>
          val ref = state.getVariablesRef(ident)
          if (!ref.isMutable) {
            throw Compiler.Error(s"Cannot assign to immutable variable ${ident.name}.", sourceIndex)
          }
          getArrayRef(ref, array.sourceIndex).subRef(state, index)
        case ArrayElement(array, index) =>
          getArrayRef(getRef(state, array, sourceIndex), array.sourceIndex).subRef(state, index)
        case StructFieldSelector(struct, selector) =>
          val structRef = getStructRef(getRef(state, struct, sourceIndex), struct.sourceIndex)
          checkStructField(state, structRef, selector, checkType = false, sourceIndex)
          structRef.subRef(state, selector)
        case Variable(ident) => state.getVariablesRef(ident)
        case _ => throw Compiler.Error(s"Invalid selector $expr", sourceIndex) // dead branch
      }
    }
    def checkMutable(state: Compiler.State[Ctx], sourceIndex: Option[SourceIndex]): Unit
    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]]
  }
  final case class AssignmentSimpleTarget[Ctx <: StatelessContext](ident: Ident)
      extends AssignmentTarget[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Type = {
      val variable = state.getVariable(ident, isWrite = true)
      state.resolveType(variable.tpe)
    }
    def checkMutable(state: Compiler.State[Ctx], sourceIndex: Option[SourceIndex]): Unit = {
      if (!state.getVariable(ident).isMutable) {
        throw Compiler.Error(s"Cannot assign to immutable variable ${ident.name}.", sourceIndex)
      }
      if (!isTypeMutable(getType(state), state)) {
        throw Compiler.Error(
          s"Cannot assign to variable ${ident.name}. Assignment only works when all of the field selectors are mutable.",
          sourceIndex
        )
      }
    }
    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = state.genStoreCode(ident)
  }
  final case class AssignmentArrayElementTarget[Ctx <: StatelessContext](
      ident: Ident,
      from: Ast.Expr[Ctx],
      index: Ast.Expr[Ctx]
  ) extends AssignmentTarget[Ctx] {
    private var arrayRef: Option[ArrayRef[Ctx]] = None
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    private def getArrayRef(
        state: Compiler.State[Ctx],
        sourceIndex: Option[SourceIndex]
    ): ArrayRef[Ctx] = {
      arrayRef match {
        case Some(ref) => ref
        case None =>
          val ref = getRef(state, from, sourceIndex).asInstanceOf[ArrayRef[Ctx]]
          arrayRef = Some(ref)
          ref
      }
    }

    def _getType(state: Compiler.State[Ctx]): Type = {
      state.getVariable(ident, isWrite = true)
      state.getArrayElementType(from, index)
    }

    @scala.annotation.tailrec
    private def invalidAssignment(
        state: Compiler.State[Ctx],
        expr: Expr[Ctx],
        isArrayMutable: Boolean,
        sourceIndex: Option[SourceIndex]
    ): Unit = {
      expr match {
        case StructFieldSelector(struct, selector) =>
          val structId = struct.getType(state) match {
            case Seq(Type.Struct(id)) => id
            case tpe => // dead branch, we have already checked the type
              throw Compiler.Error(s"Expected struct type, got $tpe", struct.sourceIndex)
          }
          throw Compiler.Error(
            s"Cannot assign to immutable element in array ${structId.name}.${selector.name}." +
              s" Assignment only works when all of the field selectors are mutable.",
            sourceIndex
          )
        case ArrayElement(expr, _) =>
          invalidAssignment(state, expr, isArrayMutable, sourceIndex)
        case Variable(ident) =>
          val errorMsg = if (isArrayMutable) {
            s"Cannot assign to immutable element in array ${ident.name}. Assignment only works when all of the field selectors are mutable."
          } else {
            s"Cannot assign to immutable variable ${ident.name}."
          }
          throw Compiler.Error(errorMsg, sourceIndex)
        case _ =>
          throw Compiler.Error(s"Invalid selector $from", from.sourceIndex) // dead branch
      }
    }

    def checkMutable(state: Compiler.State[Ctx], sourceIndex: Option[SourceIndex]): Unit = {
      val arrayRef = getArrayRef(state, sourceIndex)
      if (!arrayRef.isMutable) {
        invalidAssignment(state, from, isArrayMutable = false, sourceIndex)
      }
      if (!isTypeMutable(arrayRef.tpe.baseType, state)) {
        invalidAssignment(state, from, isArrayMutable = true, sourceIndex)
      }
    }

    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
      getArrayRef(state, None).genStoreCode(state, index)
    }
  }
  final case class AssignmentStructFieldTarget[Ctx <: StatelessContext](
      ident: Ident,
      from: Ast.Expr[Ctx],
      selector: Ast.Ident
  ) extends AssignmentTarget[Ctx] {
    private var structRef: Option[StructRef[Ctx]] = None
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    private def getStructRef(
        state: Compiler.State[Ctx],
        sourceIndex: Option[SourceIndex]
    ): StructRef[Ctx] = {
      structRef match {
        case Some(ref) => ref
        case None =>
          val ref = getRef(state, from, sourceIndex).asInstanceOf[StructRef[Ctx]]
          structRef = Some(ref)
          ref
      }
    }
    def _getType(state: Compiler.State[Ctx]): Type = {
      state.getVariable(ident, isWrite = true)
      state.getStructFieldType(from, selector)
    }

    def checkMutable(state: Compiler.State[Ctx], sourceIndex: Option[SourceIndex]): Unit = {
      val structRef = getStructRef(state, sourceIndex)
      checkStructField(state, structRef, selector, checkType = true, sourceIndex)
    }

    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
      getStructRef(state, None).genStoreCode(state, selector)
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
      Ident(s"${enumId.name}.${field.name}").atSourceIndex(field.sourceIndex)
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
      val argsType  = args.flatMap(_.getType(state))
      if (argsType.exists(t => t.isArrayType || t.isStructType)) {
        throw Compiler.Error(
          s"Array and struct types are not supported for event ${quote(s"${state.typeId.name}.${id.name}")}",
          sourceIndex
        )
      }
      eventInfo.checkFieldTypes(state, argsType, args.headOption.flatMap(_.sourceIndex))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val eventIndex = {
        val index = state.eventsInfo.map(_.typeId).indexOf(id)
        // `check` method ensures that this event is defined
        assume(index >= 0)

        Const[Ctx](Val.I256(I256.from(index))).genCode(state)
      }
      val logOpCode = Compiler.genLogs(args.length, id.sourceIndex)
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
        throw Compiler.Error(s"Cannot assign $rightTypes to $leftTypes", sourceIndex)
      }
      targets.foreach(_.checkMutable(state, sourceIndex))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      rhs.genCode(state) ++ targets.flatMap(_.genStore(state)).reverse.flatten
    }
  }
  sealed trait CallStatement[Ctx <: StatelessContext] extends Statement[Ctx] {
    def checkReturnValueUsed(
        state: Compiler.State[Ctx],
        typeId: TypeId,
        funcId: FuncId,
        retTypes: Seq[Type]
    ): Unit = {
      if (retTypes.nonEmpty && retTypes != Seq(Type.Panic)) {
        state.warningUnusedCallReturn(typeId, funcId)
      }
    }
  }
  final case class FuncCall[Ctx <: StatelessContext](
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends CallStatement[Ctx]
      with CallAst[Ctx] {
    def ignoreReturn: Boolean = true

    def getFunc(state: Compiler.State[Ctx]): Compiler.FuncInfo[Ctx] = state.getFunc(id)

    override def check(state: Compiler.State[Ctx]): Unit = {
      checkApproveAssets(state)
      val funcInfo = getFunc(state)
      val retTypes = positionedError(funcInfo.getReturnType(args.flatMap(_.getType(state)), state))
      checkReturnValueUsed(state, state.typeId, id, retTypes)
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.addInternalCall(
        id
      ) // don't put this in _getType, otherwise the statement might get skipped
      _genCode(state)
    }
  }
  final case class StaticContractFuncCall[Ctx <: StatelessContext](
      contractId: TypeId,
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends CallStatement[Ctx]
      with CallAst[Ctx] {
    def ignoreReturn: Boolean = true

    def getFunc(state: Compiler.State[Ctx]): Compiler.ContractFunc[Ctx] =
      state.getFunc(contractId, id)

    override def check(state: Compiler.State[Ctx]): Unit = {
      checkApproveAssets(state)
      val funcInfo = getFunc(state)
      checkStaticContractFunction(contractId, id, funcInfo)
      val retTypes = positionedError(funcInfo.getReturnType(args.flatMap(_.getType(state)), state))
      checkReturnValueUsed(state, contractId, id, retTypes)
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      _genCode(state)
    }
  }
  final case class ContractCall(
      obj: Expr[StatefulContext],
      callId: FuncId,
      approveAssets: Seq[ApproveAsset[StatefulContext]],
      args: Seq[Expr[StatefulContext]]
  ) extends CallStatement[StatefulContext]
      with ContractCallBase {
    override def check(state: Compiler.State[StatefulContext]): Unit = {
      checkApproveAssets(state)
      val (contractId, retTypes) = _getTypeBase(state)
      checkReturnValueUsed(state, contractId, callId, retTypes)
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
  final case class While[Ctx <: StatelessContext](
      condition: Expr[Ctx],
      body: Seq[Statement[Ctx]]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of conditional expr ${quote(condition)}", sourceIndex)
      }
      body.foreach(_.check(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val bodyIR   = body.flatMap(_.genCode(state))
      val condIR   = Statement.getCondIR(condition, state, bodyIR.length + 1)
      val whileLen = condIR.length + bodyIR.length + 1
      if (whileLen > 0xff) {
        // TODO: support long branches
        throw Compiler.Error(s"Too many instructions for if-else branches", sourceIndex)
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
        throw Compiler.Error(s"Invalid condition type: $condition", sourceIndex)
      }
      update.check(state)
      body.foreach(_.check(state))
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
      state.checkReturn(exprs.flatMap(_.getType(state)), sourceIndex)
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      exprs.flatMap(_.genCode(state)) :+ Return
  }

  final case class Debug[Ctx <: StatelessContext](
      stringParts: AVector[Val.ByteVec],
      interpolationParts: Seq[Expr[Ctx]]
  ) extends Statement[Ctx] {
    def check(state: Compiler.State[Ctx]): Unit = {
      interpolationParts.foreach(_.getType(state))
    }

    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      if (state.allowDebug) {
        interpolationParts.flatMap(_.genCode(state)) :+
          vm.DEBUG(stringParts)
      } else {
        Seq.empty
      }
    }
  }

  object TemplateVar {
    private val arraySuffix  = "-template-array"
    private val structSuffix = "-template-struct"

    @inline private[ralph] def rename(ident: Ident, tpe: Type): Ident = {
      tpe match {
        case _: Type.FixedSizeArray => Ident(s"_${ident.name}$arraySuffix")
        case _: Type.Struct         => Ident(s"_${ident.name}$structSuffix")
        case _                      => ident
      }
    }
  }

  final case class GlobalState(structs: Seq[Struct]) {
    private val flattenSizeCache = mutable.Map.empty[Type, Int]
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def flattenSize(tpe: Type, accessedTypes: Seq[TypeId]): Int = {
      tpe match {
        case Type.NamedType(id) =>
          if (accessedTypes.contains(id)) {
            throw Compiler.Error(
              s"These structs ${quote(accessedTypes.map(_.name))} have circular references",
              id.sourceIndex
            )
          }
          structs.find(_.id == id) match {
            case Some(struct) =>
              struct.fields.map(f => getFlattenSize(f.tpe, accessedTypes :+ id)).sum
            case None => 1
          }
        case Type.FixedSizeArray(baseType, size) =>
          size * flattenSize(baseType, accessedTypes)
        case Type.Struct(id) => flattenSize(Type.NamedType(id), accessedTypes)
        case _               => 1
      }
    }

    private def getFlattenSize(tpe: Type, accessedTypes: Seq[TypeId]): Int = {
      flattenSizeCache.get(tpe) match {
        case Some(size) => size
        case None =>
          val size = flattenSize(tpe, accessedTypes)
          flattenSizeCache(tpe) = size
          size
      }
    }

    private val typeCache: mutable.Map[Type, Type] = mutable.Map.empty
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def _resolveType(tpe: Type): Type = {
      tpe match {
        case t: Type.NamedType =>
          structs.find(_.id == t.id) match {
            case Some(struct) => struct.tpe
            case None         => Type.Contract(t.id)
          }
        case Type.FixedSizeArray(baseType, size) =>
          Type.FixedSizeArray(resolveType(baseType), size)
        case _ => tpe
      }
    }

    @inline def resolveType(tpe: Type): Type = {
      tpe match {
        case _: Type.NamedType | _: Type.FixedSizeArray =>
          typeCache.get(tpe) match {
            case Some(tpe) => tpe
            case None =>
              val resolvedType = _resolveType(tpe)
              typeCache.update(tpe, resolvedType)
              resolvedType
          }
        case _ => tpe
      }
    }

    @inline def resolveTypes(types: Seq[Type]): Seq[Type] = types.map(resolveType)

    def flattenTypeLength(types: Seq[Type]): Int = {
      types.foldLeft(0) { case (acc, tpe) =>
        tpe match {
          case _: Type.FixedSizeArray | _: Type.NamedType | _: Type.Struct =>
            acc + getFlattenSize(tpe, Seq.empty)
          case _ => acc + 1
        }
      }
    }

    def getStruct(typeId: Ast.TypeId): Ast.Struct = {
      structs.find(_.id == typeId) match {
        case Some(struct) => struct
        case None =>
          throw Compiler.Error(s"Struct ${quote(typeId.name)} does not exist", typeId.sourceIndex)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def flattenTypeMutability(tpe: Type, isMutable: Boolean): Seq[Boolean] = {
      val resolvedType = resolveType(tpe)
      if (isMutable) {
        resolvedType match {
          case Type.FixedSizeArray(baseType, size) =>
            val array = flattenTypeMutability(baseType, isMutable)
            Seq.fill(size)(array).flatten
          case Type.Struct(id) =>
            getStruct(id).fields.flatMap(field =>
              flattenTypeMutability(resolveType(field.tpe), field.isMutable && isMutable)
            )
          case _ => Seq(isMutable)
        }
      } else {
        Seq.fill(flattenTypeLength(Seq(resolvedType)))(false)
      }
    }
  }

  sealed trait ContractT[Ctx <: StatelessContext] extends UniqueDef with Entity {
    def ident: TypeId
    def templateVars: Seq[Argument]
    def fields: Seq[Argument]
    def funcs: Seq[FuncDef[Ctx]]

    def name: String = ident.name

    def builtInContractFuncs(globalState: GlobalState): Seq[Compiler.ContractFunc[Ctx]]

    private var functionTable: Option[Map[FuncId, Compiler.ContractFunc[Ctx]]] = None

    def funcTable(globalState: GlobalState): Map[FuncId, Compiler.ContractFunc[Ctx]] = {
      functionTable match {
        case Some(funcs) => funcs
        case None =>
          val builtInFuncs = builtInContractFuncs(globalState)
          var table = Compiler.SimpleFunc
            .from(funcs)
            .map(f => f.id -> f)
            .toMap[FuncId, Compiler.ContractFunc[Ctx]]
          builtInFuncs.foreach(func =>
            table = table + (FuncId(func.name, isBuiltIn = true) -> func)
          )
          if (table.size != (funcs.size + builtInFuncs.length)) {
            val (duplicates, sourceIndex) = UniqueDef.duplicates(funcs)
            throw Compiler.Error(
              s"These functions are defined multiple times: $duplicates",
              sourceIndex
            )
          }
          functionTable = Some(table)
          table
      }
    }

    private def addTemplateVars(state: Compiler.State[Ctx]): Unit = {
      templateVars.foreach { templateVar =>
        val tpe   = state.resolveType(templateVar.tpe)
        val ident = TemplateVar.rename(templateVar.ident, tpe)
        state.addTemplateVariable(ident, tpe)
      }
      if (state.templateVarIndex >= Compiler.State.maxVarIndex) {
        throw Compiler.Error(
          s"Number of template variables more than ${Compiler.State.maxVarIndex}",
          ident.sourceIndex
        )
      }
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      state.setCheckPhase()
      state.checkArguments(fields)
      addTemplateVars(state)
      fields.foreach(field =>
        state.addFieldVariable(
          field.ident,
          state.resolveType(field.tpe),
          field.isMutable,
          field.isUnused,
          isGenerated = false
        )
      )
      funcs.foreach(_.check(state))
      state.checkUnusedFields()
      state.checkUnassignedMutableFields()
    }

    def genMethods(state: Compiler.State[Ctx]): AVector[Method[Ctx]] = {
      AVector.from(funcs.view.map(_.genMethod(state)))
    }

    def genCode(state: Compiler.State[Ctx]): VmContract[Ctx]
  }

  final case class AssetScript(
      ident: TypeId,
      templateVars: Seq[Argument],
      funcs: Seq[FuncDef[StatelessContext]],
      structs: Seq[Struct]
  ) extends ContractT[StatelessContext] {
    val fields: Seq[Argument] = Seq.empty

    def builtInContractFuncs(
        globalState: GlobalState
    ): Seq[Compiler.ContractFunc[StatelessContext]] = Seq.empty

    def genCode(state: Compiler.State[StatelessContext]): StatelessScript = {
      state.setGenCodePhase()
      StatelessScript
        .from(genMethods(state))
        .getOrElse(
          throw Compiler.Error(s"No methods found in ${quote(ident.name)}", ident.sourceIndex)
        )
    }

    def genCodeFull(state: Compiler.State[StatelessContext]): StatelessScript = {
      check(state)
      val script = genCode(state)
      StaticAnalysis.checkMethodsStateless(this, script.methods, state)
      script
    }
  }

  sealed trait ContractWithState extends ContractT[StatefulContext] {
    def inheritances: Seq[Inheritance]

    def templateVars: Seq[Argument]
    def fields: Seq[Argument]
    def events: Seq[EventDef]
    def constantVars: Seq[ConstantVarDef]
    def enums: Seq[EnumDef]

    def builtInContractFuncs(
        globalState: GlobalState
    ): Seq[Compiler.ContractFunc[StatefulContext]] = Seq.empty

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
      Compiler.Error(s"TxScript ${ident.name} should not contain any $tpe", sourceIndex)
    def constantVars: Seq[ConstantVarDef] = throw error("constant variable")
    def enums: Seq[EnumDef]               = throw error("enum")
    def getTemplateVarsSignature(): String =
      s"TxScript ${name}(${templateVars.map(_.signature).mkString(",")})"
    def getTemplateVarsNames(): AVector[String] = AVector.from(templateVars.view.map(_.ident.name))
    def getTemplateVarsTypes(): AVector[String] =
      AVector.from(templateVars.view.map(_.tpe.signature))
    def getTemplateVarsMutability(): AVector[Boolean] =
      AVector.from(templateVars.view.map(_.isMutable))

    def withTemplateVarDefs(globalState: GlobalState): TxScript = {
      val templateVarDefs = templateVars.foldLeft(Seq.empty[Statement[StatefulContext]]) {
        case (acc, arg) =>
          val argType = globalState.resolveType(arg.tpe)
          argType match {
            case _: Type.FixedSizeArray | _: Type.Struct =>
              acc :+ VarDef(
                Seq(NamedVar(mutable = false, arg.ident)),
                Variable(TemplateVar.rename(arg.ident, argType))
              )
            case _ => acc
          }
      }
      val newFuncs = funcs.map(func => func.copy(bodyOpt = Some(templateVarDefs ++ func.body)))
      this.copy(funcs = newFuncs)
    }

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def genCode(state: Compiler.State[StatefulContext]): StatefulScript = {
      state.setGenCodePhase()
      val methods = genMethods(state)
      StatefulScript
        .from(methods)
        .getOrElse(
          throw Compiler.Error(
            "Expected the 1st function to be public and the other functions to be private for tx script",
            sourceIndex
          )
        )
    }

    def genCodeFull(state: Compiler.State[StatefulContext]): StatefulScript = {
      check(state)
      val script = genCode(state)
      StaticAnalysis.checkMethodsStateful(this, script.methods, state)
      script
    }
  }

  sealed trait Inheritance extends Positioned {
    def parentId: TypeId
  }
  final case class ContractInheritance(parentId: TypeId, idents: Seq[Ident]) extends Inheritance
  final case class InterfaceInheritance(parentId: TypeId)                    extends Inheritance
  final case class Contract(
      stdIdEnabled: Option[Boolean],
      stdInterfaceId: Option[StdInterfaceId],
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
    lazy val hasStdIdField: Boolean = stdIdEnabled.exists(identity) && stdInterfaceId.nonEmpty
    lazy val contractFields: Seq[Argument] = if (hasStdIdField) fields :+ Ast.stdArg else fields
    def getFieldsSignature(): String =
      s"Contract ${name}(${contractFields.map(_.signature).mkString(",")})"
    def getFieldNames(): AVector[String] = AVector.from(contractFields.view.map(_.ident.name))
    def getFieldTypes(): AVector[String] = AVector.from(contractFields.view.map(_.tpe.signature))
    def getFieldMutability(): AVector[Boolean] = AVector.from(contractFields.view.map(_.isMutable))

    override def builtInContractFuncs(
        globalState: GlobalState
    ): Seq[Compiler.ContractFunc[StatefulContext]] = {
      val stdInterfaceIdOpt = if (hasStdIdField) stdInterfaceId else None
      Seq(
        BuiltIn.encodeImmFields(stdInterfaceIdOpt, fields, globalState),
        BuiltIn.encodeMutFields(fields, globalState),
        BuiltIn.encodeFields(stdInterfaceIdOpt, fields, globalState)
      )
    }

    private def checkFuncs(): Unit = {
      if (funcs.length < 1) {
        throw Compiler.Error(
          s"No function found in Contract ${quote(ident.name)}",
          ident.sourceIndex
        )
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
          throw Compiler.Error(s"$kind ${id.name} can not be inherited", id.sourceIndex)
        }
      }
    }

    override def check(state: Compiler.State[StatefulContext]): Unit = {
      state.setCheckPhase()
      checkFuncs()
      checkConstants(state)
      checkInheritances(state)
      super.check(state)
    }

    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      assume(!isAbstract)
      state.setGenCodePhase()
      val methods = genMethods(state)
      val fieldsLength =
        state.flattenTypeLength(fields.map(_.tpe)) + (if (hasStdIdField) 1 else 0)
      StatefulContract(fieldsLength, methods)
    }

    // the state must have been updated in the check pass
    def buildCheckExternalCallerTable(
        state: Compiler.State[StatefulContext]
    ): mutable.Map[FuncId, Boolean] = {
      val checkExternalCallerTable = mutable.Map.empty[FuncId, Boolean]
      funcs.foreach(func => checkExternalCallerTable(func.id) = false)

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
        if (!checkExternalCallerTable(func.id)) {
          checkExternalCallerTable(func.id) = true
          if (func.isPrivate) { // indirect check external caller should be in private methods
            updateCheckedRecursivelyForPrivateMethod(func.id)
          }
        }
      }

      funcs.foreach { func =>
        if (!func.isPublic && func.hasCheckExternalCallerAnnotation) {
          state.warnPrivateFuncHasCheckExternalCaller(ident, func.id)
        }
        if (func.hasDirectCheckExternalCaller()) {
          updateCheckedRecursively(func)
        }
      }
      checkExternalCallerTable
    }
  }

  final case class ContractInterface(
      stdId: Option[StdInterfaceId],
      ident: TypeId,
      funcs: Seq[FuncDef[StatefulContext]],
      events: Seq[EventDef],
      inheritances: Seq[InterfaceInheritance]
  ) extends ContractWithState {
    def error(tpe: String): Compiler.Error =
      Compiler.Error(
        s"Interface ${quote(ident.name)} should not contain any ${quote(tpe)}",
        sourceIndex
      )

    def templateVars: Seq[Argument]       = throw error("template variable")
    def fields: Seq[Argument]             = throw error("field")
    def getFieldsSignature(): String      = throw error("field")
    def getFieldTypes(): Seq[String]      = throw error("field")
    def constantVars: Seq[ConstantVarDef] = throw error("constant variable")
    def enums: Seq[EnumDef]               = throw error("enum")

    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      throw Compiler.Error(s"Interface ${quote(ident.name)} should not generate code", sourceIndex)
    }
  }

  final case class MultiContract(
      contracts: Seq[ContractWithState],
      structs: Seq[Struct],
      dependencies: Option[Map[TypeId, Seq[TypeId]]]
  ) extends Positioned {
    lazy val globalState = GlobalState(structs)

    lazy val contractsTable = contracts.map { contract =>
      val kind = contract match {
        case _: Ast.ContractInterface =>
          Compiler.ContractKind.Interface
        case _: Ast.TxScript =>
          Compiler.ContractKind.TxScript
        case txContract: Ast.Contract =>
          Compiler.ContractKind.Contract(txContract.isAbstract)
      }
      contract.ident -> Compiler.ContractInfo(kind, contract.funcTable(globalState))
    }.toMap

    def get(contractIndex: Int): ContractWithState = {
      if (contractIndex >= 0 && contractIndex < contracts.size) {
        contracts(contractIndex)
      } else {
        throw Compiler.Error(s"Invalid contract index $contractIndex", None)
      }
    }

    private def getContract(typeId: TypeId): ContractWithState = {
      contracts.find(_.ident.name == typeId.name) match {
        case None =>
          throw Compiler.Error(s"Contract ${quote(typeId.name)} does not exist", typeId.sourceIndex)
        case Some(ts: TxScript) =>
          throw Compiler.Error(
            s"Expected contract ${quote(typeId.name)}, but was script",
            ts.sourceIndex
          )
        case Some(contract: ContractWithState) => contract
      }
    }

    def isContract(typeId: TypeId): Boolean = {
      contracts.find(_.ident.name == typeId.name) match {
        case None =>
          throw Compiler.Error(s"Contract ${quote(typeId.name)} does not exist", typeId.sourceIndex)
        case Some(contract: Contract) if !contract.isAbstract => true
        case _                                                => false
      }
    }

    def getInterface(typeId: TypeId): ContractInterface = {
      getContract(typeId) match {
        case interface: ContractInterface => interface
        case _ =>
          throw Compiler.Error(s"Interface ${typeId.name} does not exist", typeId.sourceIndex)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def buildDependencies(
        contract: ContractWithState,
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        visited: mutable.Set[TypeId]
    ): Unit = {
      if (!visited.add(contract.ident)) {
        throw Compiler.Error(
          s"Cyclic inheritance detected for contract ${contract.ident.name}",
          contract.sourceIndex
        )
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
      UniqueDef.checkDuplicates(contracts ++ structs, "TxScript/Contract/Interface/Struct")

      val parentsCache = buildDependencies()
      val newContracts: Seq[ContractWithState] = contracts.map {
        case script: TxScript =>
          script.withTemplateVarDefs(globalState).atSourceIndex(script.sourceIndex)
        case c: Contract =>
          val (stdIdEnabled, stdId, funcs, events, constantVars, enums) =
            MultiContract.extractDefs(parentsCache, c)
          Contract(
            Some(stdIdEnabled),
            stdId,
            c.isAbstract,
            c.ident,
            c.templateVars,
            c.fields,
            funcs,
            events,
            constantVars,
            enums,
            c.inheritances
          ).atSourceIndex(c.sourceIndex)
        case i: ContractInterface =>
          val (_, stdId, funcs, events, _, _) = MultiContract.extractDefs(parentsCache, i)
          ContractInterface(stdId, i.ident, funcs, events, i.inheritances).atSourceIndex(
            i.sourceIndex
          )
      }
      val dependencies = Map.from(parentsCache.map(p => (p._1, p._2.map(_.ident))))
      MultiContract(newContracts, structs, Some(dependencies))
    }

    def genStatefulScripts()(implicit compilerOptions: CompilerOptions): AVector[CompiledScript] = {
      AVector.from(contracts.view.zipWithIndex.collect { case (_: TxScript, index) =>
        genStatefulScript(index)
      })
    }

    def genStatefulScript(contractIndex: Int)(implicit
        compilerOptions: CompilerOptions
    ): CompiledScript = {
      val state = Compiler.State.buildFor(this, contractIndex)
      get(contractIndex) match {
        case script: TxScript =>
          val statefulScript = script.genCodeFull(state)
          val warnings       = state.getWarnings
          state.allowDebug = true
          val statefulDebugScript = script.genCode(state)
          CompiledScript(statefulScript, script, warnings, statefulDebugScript)
        case c: Contract =>
          throw Compiler.Error(s"The code is for Contract, not for TxScript", c.sourceIndex)
        case ci: ContractInterface =>
          throw Compiler.Error(s"The code is for Interface, not for TxScript", ci.sourceIndex)
      }
    }

    def genStatefulContracts()(implicit
        compilerOptions: CompilerOptions
    ): AVector[(CompiledContract, Int)] = {
      val states = AVector.tabulate(contracts.length)(Compiler.State.buildFor(this, _))
      val statefulContracts = AVector.from(contracts.view.zipWithIndex.collect {
        case (contract: Contract, index) if !contract.isAbstract =>
          val state = states(index)
          contract.check(state)
          state.allowDebug = true
          val statefulDebugContract = contract.genCode(state)
          (statefulDebugContract, contract, state, index)
      })
      StaticAnalysis.checkExternalCalls(this, states)
      statefulContracts.map { case (statefulDebugContract, contract, state, index) =>
        val statefulContract = genReleaseCode(contract, statefulDebugContract, state)
        StaticAnalysis.checkMethods(contract, statefulDebugContract, state)
        CompiledContract(
          statefulContract,
          contract,
          state.getWarnings,
          statefulDebugContract
        ) -> index
      }
    }

    def genReleaseCode(
        contract: Contract,
        debugCode: StatefulContract,
        state: Compiler.State[StatefulContext]
    ): StatefulContract = {
      if (debugCode.methods.exists(_.instrs.exists(_.isInstanceOf[DEBUG]))) {
        state.allowDebug = false
        contract.genCode(state)
      } else {
        debugCode
      }
    }

    def genStatefulContract(contractIndex: Int)(implicit
        compilerOptions: CompilerOptions
    ): CompiledContract = {
      get(contractIndex) match {
        case contract: Contract =>
          if (contract.isAbstract) {
            throw Compiler.Error(
              s"Code generation is not supported for abstract contract ${quote(contract.ident.name)}",
              contract.sourceIndex
            )
          }
          val statefulContracts = genStatefulContracts()
          statefulContracts.find(_._2 == contractIndex) match {
            case Some(v) => v._1
            case None => // should never happen
              throw Compiler.Error(
                s"Failed to compile contract ${contract.ident.name}",
                contract.sourceIndex
              )
          }
        case ts: TxScript =>
          throw Compiler.Error(s"The code is for TxScript, not for Contract", ts.sourceIndex)
        case ci: ContractInterface =>
          throw Compiler.Error(s"The code is for Interface, not for Contract", ci.sourceIndex)
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
          .find(_.ident.name == ident.name)
          .getOrElse(
            throw Compiler.Error(
              s"Inherited field ${quote(ident.name)} does not exist in contract ${quote(contract.name)}",
              ident.sourceIndex
            )
          )
      }
      if (fields != parentContract.fields) {
        throw Compiler.Error(
          s"Invalid contract inheritance fields, expected ${quote(parentContract.fields)}, got ${quote(fields)}",
          fields.headOption.flatMap(_.sourceIndex)
        )
      }
    }

    @inline private[ralph] def getStdId(
        interfaces: Seq[ContractInterface]
    ): Option[StdInterfaceId] = {
      interfaces.foldLeft[Option[StdInterfaceId]](None) { case (parentStdIdOpt, interface) =>
        (parentStdIdOpt, interface.stdId) match {
          case (Some(parentStdId), Some(stdId)) =>
            if (stdId.bytes == parentStdId.bytes) {
              throw Compiler.Error(
                s"The std id of interface ${interface.ident.name} is the same as parent interface",
                interface.sourceIndex
              )
            }
            if (!stdId.bytes.startsWith(parentStdId.bytes)) {
              throw Compiler.Error(
                s"The std id of interface ${interface.ident.name} should start with ${Hex
                    .toHexString(parentStdId.bytes.drop(Ast.StdInterfaceIdPrefix.length))}",
                interface.sourceIndex
              )
            }
            Some(stdId)
          case (Some(parentStdId), None) => Some(parentStdId)
          case (None, stdId)             => stdId
        }
      }
    }

    @inline private[ralph] def getStdIdEnabled(
        contracts: Seq[Contract],
        typeId: Ast.TypeId
    ): Boolean = {
      contracts
        .foldLeft[Option[Boolean]](None) {
          case (None, contract) => contract.stdIdEnabled
          case (v, contract) =>
            if (contract.stdIdEnabled.nonEmpty && contract.stdIdEnabled != v) {
              throw Compiler.Error(
                s"There are different std id enabled options on the inheritance chain of contract ${typeId.name}",
                typeId.sourceIndex
              )
            }
            v
        }
        .getOrElse(true)
    }

    // scalastyle:off method.length
    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def extractDefs(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        contract: ContractWithState
    ): (
        Boolean,
        Option[StdInterfaceId],
        Seq[FuncDef[StatefulContext]],
        Seq[EventDef],
        Seq[ConstantVarDef],
        Seq[EnumDef]
    ) = {
      val parents                       = parentsCache(contract.ident)
      val (allContracts, allInterfaces) = (parents :+ contract).partition(_.isInstanceOf[Contract])

      val sortedInterfaces =
        sortInterfaces(parentsCache, allInterfaces.map(_.asInstanceOf[ContractInterface]))

      ensureChainedInterfaces(sortedInterfaces)

      val stdId        = getStdId(sortedInterfaces)
      val stdIdEnabled = getStdIdEnabled(allContracts.map(_.asInstanceOf[Contract]), contract.ident)

      val allFuncs                             = (sortedInterfaces ++ allContracts).flatMap(_.funcs)
      val (abstractFuncs, nonAbstractFuncs)    = allFuncs.partition(_.bodyOpt.isEmpty)
      val (unimplementedFuncs, allUniqueFuncs) = checkFuncs(abstractFuncs, nonAbstractFuncs)
      val constantVars                         = allContracts.flatMap(_.constantVars)
      val enums                                = mergeEnums(allContracts.flatMap(_.enums))

      val contractEvents = allContracts.flatMap(_.events)
      val events         = sortedInterfaces.flatMap(_.events) ++ contractEvents

      val resultFuncs = contract match {
        case txs: TxScript =>
          throw Compiler.Error("Extract definitions from TxScript is unexpected", txs.sourceIndex)
        case txContract: Contract =>
          if (!txContract.isAbstract && unimplementedFuncs.nonEmpty) {
            val methodNames = unimplementedFuncs.map(_.name).mkString(",")
            throw Compiler.Error(
              s"Contract ${txContract.name} has unimplemented methods: $methodNames",
              txContract.sourceIndex
            )
          }

          allUniqueFuncs
        case interface: ContractInterface =>
          if (nonAbstractFuncs.nonEmpty) {
            val methodNames = nonAbstractFuncs.map(_.name).mkString(",")
            throw Compiler.Error(
              s"Interface ${interface.name} has implemented methods: $methodNames",
              interface.sourceIndex
            )
          }
          unimplementedFuncs
      }

      (stdIdEnabled, stdId, resultFuncs, events, constantVars, enums)
    }
    // scalastyle:on method.length

    @tailrec
    def ensureChainedInterfaces(sortedInterfaces: Seq[ContractInterface]): Unit = {
      if (sortedInterfaces.length >= 2) {
        val parent = sortedInterfaces(0)
        val child  = sortedInterfaces(1)
        if (!child.inheritances.exists(_.parentId.name == parent.ident.name)) {
          throw Compiler.Error(
            s"Only single inheritance is allowed. Interface ${child.ident.name} does not inherit from ${parent.ident.name}",
            child.sourceIndex
          )
        }

        ensureChainedInterfaces(sortedInterfaces.drop(1))
      }
    }

    private def sortInterfaces(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        allInterfaces: Seq[ContractInterface]
    ): Seq[ContractInterface] = {
      allInterfaces.sortBy(interface => parentsCache(interface.ident).length)
    }

    def mergeEnums(enums: Seq[EnumDef]): Seq[EnumDef] = {
      val mergedEnums = mutable.Map.empty[TypeId, mutable.ArrayBuffer[EnumField]]
      enums.foreach { enumDef =>
        mergedEnums.get(enumDef.id) match {
          case Some(fields) =>
            // enum fields will never be empty
            val expectedType = enumDef.fields(0).value.tpe
            val haveType     = fields(0).value.tpe
            if (expectedType != haveType) {
              throw Compiler.Error(
                s"There are different field types in the enum ${enumDef.id.name}: $expectedType,$haveType",
                fields(0).sourceIndex
              )
            }
            val conflictFields = enumDef.fields.filter(f => fields.exists(_.name == f.name))
            if (conflictFields.nonEmpty) {
              throw Compiler.Error(
                s"There are conflict fields in the enum ${enumDef.id.name}: ${conflictFields.map(_.name).mkString(",")}",
                conflictFields.headOption.flatMap(_.sourceIndex)
              )
            }
            fields.appendAll(enumDef.fields)
          case None => mergedEnums(enumDef.id) = mutable.ArrayBuffer.from(enumDef.fields)
        }
      }
      mergedEnums.view.map(pair => EnumDef(pair._1, pair._2.toSeq)).toSeq
    }

    def checkFuncs(
        abstractFuncs: Seq[FuncDef[StatefulContext]],
        nonAbstractFuncs: Seq[FuncDef[StatefulContext]]
    ): (Seq[FuncDef[StatefulContext]], Seq[FuncDef[StatefulContext]]) = {
      val nonAbstractFuncSet = nonAbstractFuncs.view.map(f => f.id.name -> f).toMap
      val abstractFuncsSet   = abstractFuncs.view.map(f => f.id.name -> f).toMap
      if (nonAbstractFuncSet.size != nonAbstractFuncs.size) {
        val (duplicates, sourceIndex) = UniqueDef.duplicates(nonAbstractFuncs)
        throw Compiler.Error(
          s"These functions are implemented multiple times: $duplicates",
          sourceIndex
        )
      }

      if (abstractFuncsSet.size != abstractFuncs.size) {
        val (duplicates, sourceIndex) = UniqueDef.duplicates(abstractFuncs)
        throw Compiler.Error(
          s"These abstract functions are defined multiple times: $duplicates",
          sourceIndex
        )
      }

      val (implementedFuncs, unimplementedFuncs) =
        abstractFuncs.partition(func => nonAbstractFuncSet.contains(func.id.name))

      implementedFuncs.foreach { abstractFunc =>
        val funcName                = abstractFunc.id.name
        val implementedAbstractFunc = nonAbstractFuncSet(funcName)
        if (implementedAbstractFunc.signature != abstractFunc.signature) {
          throw Compiler.Error(
            s"Function ${quote(funcName)} is implemented with wrong signature",
            implementedAbstractFunc.sourceIndex
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
