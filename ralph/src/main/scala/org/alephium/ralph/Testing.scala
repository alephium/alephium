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

import org.alephium.protocol.model._
import org.alephium.protocol.vm.{BlockHash => _, _}
import org.alephium.util.{AVector, Hex, TimeStamp, U256}

object Testing {
  private def checkAndGetValue[Ctx <: StatelessContext, V <: Val](
      state: Compiler.State[Ctx],
      expr: Ast.Expr[Ctx],
      tpe: Val.Type,
      name: String
  ): V = {
    expr match {
      case Ast.Const(v: V @unchecked) if v.tpe == tpe => v
      case Ast.ALPHTokenId() if tpe == Val.ByteVec =>
        Val.ByteVec(TokenId.alph.bytes).asInstanceOf[V]
      case Ast.Variable(id) =>
        state.getVariable(id) match {
          case Compiler.VarInfo.Constant(_, _, v: V @unchecked, _, _) if v.tpe == tpe => v
          case _ =>
            throw Compiler.Error(s"Invalid $name expr, expected a constant expr", expr.sourceIndex)
        }
      case _ =>
        throw Compiler.Error(s"Invalid $name expr, expected a constant expr", expr.sourceIndex)
    }
  }

  sealed trait SettingDef[Ctx <: StatelessContext] extends Ast.UniqueDef with Ast.Positioned
  final case class GroupDef[Ctx <: StatelessContext](expr: Ast.Expr[Ctx]) extends SettingDef[Ctx] {
    def name: String = "group"
    def compile(state: Compiler.State[Ctx]): Int = {
      val value = checkAndGetValue[Ctx, Val.U256](state, expr, Val.U256, name)
      value.v.toBigInt.intValue()
    }
  }
  final case class BlockHashDef[Ctx <: StatelessContext](expr: Ast.Expr[Ctx])
      extends SettingDef[Ctx] {
    def name: String = "blockHash"
    def compile(state: Compiler.State[Ctx]): BlockHash = {
      val value = checkAndGetValue[Ctx, Val.ByteVec](state, expr, Val.ByteVec, name)
      BlockHash
        .from(value.bytes)
        .getOrElse(
          throw Compiler.Error(s"Invalid block hash ${Hex.toHexString(value.bytes)}", sourceIndex)
        )
    }
  }
  final case class BlockTimeStampDef[Ctx <: StatelessContext](expr: Ast.Expr[Ctx])
      extends SettingDef[Ctx] {
    def name: String = "blockTimeStamp"
    def compile(state: Compiler.State[Ctx]): TimeStamp = {
      val value = checkAndGetValue[Ctx, Val.U256](state, expr, Val.U256, name)
      TimeStamp.unsafe(value.v.toBigInt.longValue())
    }
  }

  final case class SettingsDef[Ctx <: StatelessContext](defs: Seq[SettingDef[Ctx]])
      extends Ast.Positioned {
    def compile(state: Compiler.State[Ctx]): SettingsValue = {
      Ast.UniqueDef.checkDuplicates(defs, "test settings")
      val groupDef          = defs.collectFirst { case g @ GroupDef(_) => g }
      val blockHashDef      = defs.collectFirst { case b @ BlockHashDef(_) => b }
      val blockTimeStampDef = defs.collectFirst { case b @ BlockTimeStampDef(_) => b }
      SettingsValue(
        groupDef.map(_.compile(state)).getOrElse(0),
        blockHashDef.map(_.compile(state)),
        blockTimeStampDef.map(_.compile(state))
      )
    }
  }
  final case class SettingsValue(
      group: Int,
      blockHash: Option[BlockHash],
      blockTimeStamp: Option[TimeStamp]
  )

  private def getApprovedToken[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tokenIdExpr: Ast.Expr[Ctx],
      amountExpr: Ast.Expr[Ctx]
  ) = {
    val tokenId =
      checkAndGetValue[Ctx, Val.ByteVec](state, tokenIdExpr, Val.ByteVec, "token id")
    val amount = checkAndGetValue[Ctx, Val.U256](state, amountExpr, Val.U256, "amount")
    TokenId.from(tokenId.bytes) match {
      case Some(token) => (token, amount.v)
      case None =>
        throw Compiler.Error(
          s"Invalid token id ${Hex.toHexString(tokenId.bytes)}",
          tokenIdExpr.sourceIndex
        )
    }
  }

  final case class ApprovedAssetsDef[Ctx <: StatelessContext](assets: Seq[Ast.ApproveAsset[Ctx]])
      extends Ast.Positioned {
    private def getApprovedTokens(
        state: Compiler.State[Ctx],
        asset: Ast.ApproveAsset[Ctx]
    ): (LockupScript, AVector[(TokenId, U256)]) = {
      val address =
        checkAndGetValue[Ctx, Val.Address](state, asset.address, Val.Address, "address")
      val tokens = asset.tokenAmounts.map { case (tokenExpr, amountExpr) =>
        getApprovedToken(state, tokenExpr, amountExpr)
      }
      val amounts = tokens.groupBy(_._1).view.mapValues { amounts =>
        amounts.foldLeft(Option(U256.Zero))((acc, v) => acc.flatMap(_.add(v._2))) match {
          case Some(amount) => amount
          case None =>
            throw Compiler.Error(s"Token amount overflow", asset.sourceIndex)
        }
      }
      (address.lockupScript, AVector.from(amounts))
    }

    def compile(state: Compiler.State[Ctx]): ApprovedAssetsValue = {
      ApprovedAssetsValue(AVector.from(assets.map(getApprovedTokens(state, _))))
    }
  }
  final case class ApprovedAssetsValue(assets: AVector[(LockupScript, AVector[(TokenId, U256)])])

  final case class CreateContractDef[Ctx <: StatelessContext](
      typeId: Ast.TypeId,
      tokens: Seq[(Ast.Expr[Ctx], Ast.Expr[Ctx])],
      fields: Seq[Ast.Expr[Ctx]],
      address: Option[Ast.Ident]
  ) extends Ast.Positioned {
    private def getContract(state: Compiler.State[Ctx]): Ast.Contract = {
      val info = state.getContractInfo(if (typeId.name == "Self") state.typeId else typeId)
      info.ast match {
        case c: Ast.Contract => c
        case _ =>
          throw Compiler.Error(
            s"Invalid type ${typeId.name}, expected a contract type",
            typeId.sourceIndex
          )
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def getValues(
        state: Compiler.State[Ctx],
        expr: Ast.Expr[Ctx],
        tpe: Type,
        isMutable: Boolean
    ): Seq[(Val, Boolean)] = {
      (state.resolveType(tpe), expr) match {
        case (t: Type.FixedSizeArray, Ast.CreateArrayExpr1(elements)) =>
          elements.flatMap(getValues(state, _, t.baseType, isMutable))
        case (t: Type.FixedSizeArray, Ast.CreateArrayExpr2(element, sizeExpr)) =>
          val elements = getValues(state, element, t.baseType, isMutable)
          val size     = checkAndGetValue[Ctx, Val.U256](state, sizeExpr, Val.U256, "array size")
          Seq.fill(size.v.toBigInt.intValue())(elements).flatten
        case (t: Type.Struct, Ast.StructCtor(_, fields)) =>
          val struct = state.getStruct(t.id)
          struct.fields.flatMap { structField =>
            val fieldDef = fields.find(_._1 == structField.ident)
            fieldDef.flatMap(_._2) match {
              case Some(expr) =>
                getValues(state, expr, structField.tpe, isMutable && structField.isMutable)
              case None =>
                val sourceIndex = fieldDef.flatMap(_._1.sourceIndex)
                throw Compiler.Error(
                  s"Expected an expr for struct field ${structField.name}",
                  sourceIndex
                )
            }
          }
        case (t: Type.Contract, Ast.ContractConv(_, expr)) =>
          val value = checkAndGetValue[Ctx, Val.ByteVec](state, expr, t.toVal, "contract field")
          Seq(value -> isMutable)
        case (t: Type.Contract, expr @ Ast.Variable(_)) =>
          val value = checkAndGetValue[Ctx, Val.ByteVec](state, expr, t.toVal, "contract field")
          Seq(value -> isMutable)
        case (t: Type, expr) if t.isPrimitive =>
          val value = checkAndGetValue[Ctx, Val](state, expr, t.toVal, "contract field")
          Seq(value -> isMutable)
        case (t, expr) =>
          throw Compiler.Error(
            s"Invalid expression, expected a constant expression of type $t",
            expr.sourceIndex
          )
      }
    }

    private def getFields(
        state: Compiler.State[Ctx],
        contract: Ast.Contract
    ): (AVector[Val], AVector[Val]) = {
      if (contract.fields.length != fields.length) {
        throw Compiler.Error(s"Invalid contract field size", sourceIndex)
      }
      val values = contract.fields.view.zipWithIndex.flatMap { case (argument, index) =>
        val fieldExpr = fields(index)
        val fieldType = state.resolveType(argument.tpe)
        if (fieldExpr.getType(state).map(_.toVal) != Seq(fieldType.toVal)) {
          throw Compiler.Error(
            s"Invalid contract field type, expected $fieldType",
            fieldExpr.sourceIndex
          )
        }
        getValues(state, fieldExpr, fieldType, argument.isMutable)
      }
      val (immFields, mutFields) = values.partition(!_._2)
      (AVector.from(immFields.map(_._1)), AVector.from(mutFields.map(_._1)))
    }

    def compile(state: Compiler.State[Ctx]): CreateContractValue = {
      val tokenAmounts           = tokens.map(token => getApprovedToken(state, token._1, token._2))
      val contract               = getContract(state)
      val (immFields, mutFields) = getFields(state, contract)
      val contractId             = ContractId.random
      address.foreach(
        state.addTestingConstant(_, Val.ByteVec(contractId.bytes), Type.Contract(contract.ident))
      )
      CreateContractValue(
        contract.ident,
        AVector.from(tokenAmounts),
        immFields,
        mutFields,
        contractId
      )
    }
  }
  final case class CreateContractValue(
      typeId: Ast.TypeId,
      tokens: AVector[(TokenId, U256)],
      immFields: AVector[Val],
      mutFields: AVector[Val],
      contractId: ContractId
  )

  final case class SingleTestDef[Ctx <: StatelessContext](
      contracts: Seq[CreateContractDef[Ctx]],
      assets: Option[ApprovedAssetsDef[Ctx]],
      body: Seq[Ast.Statement[Ctx]]
  ) extends Ast.Positioned {
    def compile(
        state: Compiler.State[Ctx],
        settings: Option[SettingsValue],
        scopeId: Ast.FuncId
    ): CompiledUnitTest[Ctx] = {
      val (selfContracts, dependencies) = contracts.partition(_.typeId.name == "Self")
      if (selfContracts.isEmpty) {
        throw Compiler.Error(s"Self contract is not defined", sourceIndex)
      }
      if (selfContracts.length > 1) {
        throw Compiler.Error(s"Self contract is defined multiple times", sourceIndex)
      }
      state.setFuncScope(scopeId)
      state.withScope(this) {
        val compiledDeps   = AVector.from(dependencies.map(_.compile(state)))
        val compiledSelf   = selfContracts(0).compile(state)
        val compiledAssets = assets.map(_.compile(state))
        body.foreach(_.check(state))
        val method = Method[Ctx](
          isPublic = true,
          usePreapprovedAssets = assets.isDefined,
          useContractAssets = false,
          usePayToContractOnly = false,
          argsLength = 0,
          localsLength = state.getLocalVarSize(scopeId),
          returnLength = 0,
          instrs = AVector.from(body.flatMap(_.genCode(state)))
        )
        CompiledUnitTest(scopeId.name, settings, compiledSelf, compiledDeps, compiledAssets, method)
      }
    }
  }

  final case class UnitTestDef[Ctx <: StatelessContext](
      name: String,
      settings: Option[SettingsDef[Ctx]],
      tests: Seq[SingleTestDef[Ctx]]
  ) extends Ast.UniqueDef
      with Ast.Positioned {
    def compile(state: Compiler.State[Ctx]): AVector[CompiledUnitTest[Ctx]] = {
      state.setGenDebugCode()

      val settingsValue = settings.map(_.compile(state))
      AVector.from(tests).mapWithIndex { case (test, index) =>
        val scopeId = Ast.FuncId(s"UnitTest:$name:$index", false)
        test.compile(state, settingsValue, scopeId)
      }
    }
  }

  final case class CompiledUnitTest[Ctx <: StatelessContext](
      name: String,
      settings: Option[SettingsValue],
      selfContract: CreateContractValue,
      dependencies: AVector[CreateContractValue],
      assets: Option[ApprovedAssetsValue],
      method: Method[Ctx]
  ) {
    lazy val contracts: AVector[CreateContractValue] = dependencies :+ selfContract
  }
}
