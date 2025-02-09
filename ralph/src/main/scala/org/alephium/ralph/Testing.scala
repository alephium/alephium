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

import scala.collection.mutable
import scala.util.Random

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{BlockHash => _, _}
import org.alephium.ralph.error.CompilerError
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

  final case class SettingDef[Ctx <: StatelessContext](name: String, value: Ast.Expr[Ctx])
      extends Ast.UniqueDef
      with Ast.Positioned

  final case class SettingsDef[Ctx <: StatelessContext](defs: Seq[SettingDef[Ctx]])
      extends Ast.Positioned {

    private def getSetting[V <: Val](
        state: Compiler.State[Ctx],
        tpe: Val.Type,
        name: String
    ): Option[V] = {
      defs.find(_.name == name).map(d => checkAndGetValue[Ctx, V](state, d.value, tpe, "group"))
    }

    private def getGroup(state: Compiler.State[Ctx]): Option[Int] = {
      getSetting[Val.U256](state, Val.U256, "group").map(_.v.toBigInt.intValue())
    }

    private def getBlockHash(state: Compiler.State[Ctx]): Option[BlockHash] = {
      getSetting[Val.ByteVec](state, Val.ByteVec, "blockHash").map { v =>
        BlockHash
          .from(v.bytes)
          .getOrElse(
            throw Compiler.Error(s"Invalid block hash ${Hex.toHexString(v.bytes)}", sourceIndex)
          )
      }
    }

    private def getBlockTimeStamp(state: Compiler.State[Ctx]): Option[TimeStamp] = {
      getSetting[Val.U256](state, Val.U256, "blockTimeStamp").map { v =>
        TimeStamp.unsafe(v.v.toBigInt.longValue())
      }
    }

    def compile(state: Compiler.State[Ctx]): SettingsValue = {
      Ast.UniqueDef.checkDuplicates(defs, "test settings")
      defs.find(d => !SettingsDef.keys.contains(d.name)).foreach { invalidDef =>
        throw Compiler.Error(
          s"Invalid setting key ${invalidDef.name}, it must be one of ${SettingsDef.keys
              .mkString("[", ",", "]")}",
          invalidDef.sourceIndex
        )
      }
      SettingsValue(
        getGroup(state).getOrElse(0),
        getBlockHash(state),
        getBlockTimeStamp(state)
      )
    }
  }
  object SettingsDef {
    val keys: Seq[String] = Seq("group", "blockHash", "blockTimeStamp")
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
    ): (LockupScript.Asset, AVector[(TokenId, U256)]) = {
      val address =
        checkAndGetValue[Ctx, Val.Address](state, asset.address, Val.Address, "address")
      val assetAddress = address.lockupScript match {
        case address: LockupScript.Asset => address
        case _ =>
          throw Compiler.Error(
            s"Invalid address ${address.toBase58}, expected an asset address",
            asset.address.sourceIndex
          )
      }
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
      (assetAddress, AVector.from(amounts))
    }

    def compile(state: Compiler.State[Ctx]): ApprovedAssetsValue = {
      ApprovedAssetsValue(AVector.from(assets.map(getApprovedTokens(state, _))))
    }
  }
  final case class ApprovedAssetsValue(
      assets: AVector[(LockupScript.Asset, AVector[(TokenId, U256)])]
  ) extends AnyVal

  final case class CreateContractDef[Ctx <: StatelessContext](
      typeId: Ast.TypeId,
      tokens: Seq[(Ast.Expr[Ctx], Ast.Expr[Ctx])],
      fields: Seq[Ast.Expr[Ctx]],
      address: Option[Ast.Ident]
  ) extends Ast.Positioned {
    def isSelfType: Boolean = typeId.name == "Self"

    private def getContract(state: Compiler.State[Ctx], typeId: Ast.TypeId): Ast.Contract = {
      state.getContractInfo(typeId).ast match {
        case c: Ast.Contract =>
          if (!c.isAbstract) {
            c
          } else {
            throw Compiler.Error(
              s"Expected a contract type ${typeId.name}, but got an abstract contract",
              typeId.sourceIndex
            )
          }
        case _ =>
          throw Compiler.Error(s"Contract ${typeId.name} does not exist", typeId.sourceIndex)
      }
    }

    private def getAbstractContract(
        state: Compiler.State[Ctx],
        typeId: Ast.TypeId
    ): Ast.Contract = {
      state.getContractInfo(typeId).ast match {
        case c: Ast.Contract if c.isAbstract => c
        case _ =>
          throw Compiler.Error(
            s"Abstract contract ${typeId.name} does not exist",
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

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def genDefaultValue(
        state: Compiler.State[Ctx],
        tpe: Type,
        isMutable: Boolean
    ): Seq[(Val, Boolean)] = {
      state.resolveType(tpe) match {
        case Type.Struct(id) =>
          state
            .getStruct(id)
            .fields
            .flatMap(field => genDefaultValue(state, field.tpe, isMutable && field.isMutable))
        case t @ Type.FixedSizeArray(baseType, _) =>
          val values = genDefaultValue(state, baseType, isMutable)
          Seq.fill(t.getArraySize)(values).flatten
        case _: Type.Contract             => Seq(Val.ByteVec(ContractId.zero.bytes) -> isMutable)
        case tpe: Type if tpe.isPrimitive => Seq(tpe.toVal.default -> isMutable)
        case tpe => throw Compiler.Error(s"Invalid type $tpe", tpe.sourceIndex)
      }
    }

    private def getFields(
        state: Compiler.State[Ctx],
        origin: Ast.Contract,
        self: Ast.Contract
    ): (AVector[Val], AVector[Val]) = {
      if (origin.fields.length != fields.length) {
        throw Compiler.Error(s"Invalid contract field size", sourceIndex)
      }
      val values = self.fields.flatMap { argument =>
        val fieldType = state.resolveType(argument.tpe)
        val index     = origin.fields.indexWhere(_.ident == argument.ident)
        if (index != -1) {
          val fieldExpr = fields(index)
          if (fieldExpr.getType(state).map(_.toVal) != Seq(fieldType.toVal)) {
            throw Compiler.Error(
              s"Invalid contract field type, expected $fieldType",
              fieldExpr.sourceIndex
            )
          }
          getValues(state, fieldExpr, fieldType, argument.isMutable)
        } else {
          genDefaultValue(state, fieldType, argument.isMutable)
        }
      }
      val (immFields, mutFields) = values.partition(!_._2)
      (AVector.from(immFields.map(_._1)), AVector.from(mutFields.map(_._1)))
    }

    def compile(state: Compiler.State[Ctx], origin: Ast.TypeId): CreateContractValue = {
      val tokenAmounts = tokens.map(token => getApprovedToken(state, token._1, token._2))
      val (selfTypeId, (immFields, mutFields)) = if (isSelfType) {
        val selfTypeId   = state.typeId
        val selfContract = getContract(state, selfTypeId)
        val originContract =
          if (selfTypeId == origin) selfContract else getAbstractContract(state, origin)
        (selfTypeId, getFields(state, originContract, selfContract))
      } else {
        val contract = getContract(state, typeId)
        (typeId, getFields(state, contract, contract))
      }
      val contractId = ContractId.random
      address.foreach(
        state.addTestingConstant(_, Val.ByteVec(contractId.bytes), Type.Contract(selfTypeId))
      )
      CreateContractValue(
        selfTypeId,
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
        name: String,
        index: Int,
        origin: Ast.TypeId
    ): CompiledUnitTest[Ctx] = {
      val (selfContracts, dependencies) = contracts.partition(_.isSelfType)
      if (selfContracts.isEmpty) {
        throw Compiler.Error(s"Self contract is not defined", sourceIndex)
      }
      if (selfContracts.length > 1) {
        throw Compiler.Error(s"Self contract is defined multiple times", sourceIndex)
      }
      val scopeId = Ast.FuncId(s"$name:$index", false)
      state.setFuncScope(scopeId)
      state.withScope(this) {
        val compiledDeps   = AVector.from(dependencies.map(_.compile(state, origin)))
        val compiledSelf   = selfContracts(0).compile(state, origin)
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
        CompiledUnitTest(name, settings, compiledSelf, compiledDeps, compiledAssets, method)
      }
    }
  }

  final case class UnitTestDef[Ctx <: StatelessContext](
      testName: String,
      settings: Option[SettingsDef[Ctx]],
      tests: Seq[SingleTestDef[Ctx]]
  ) extends Ast.UniqueDef
      with Ast.Positioned
      with Ast.OriginContractInfo {
    lazy val name: String = origin.map(typeId => s"${typeId.name}:$testName").getOrElse(testName)

    def compile(state: Compiler.State[Ctx]): AVector[CompiledUnitTest[Ctx]] = {
      state.setGenDebugCode()

      val settingsValue = settings.map(_.compile(state))
      AVector.from(tests).mapWithIndex { case (test, index) =>
        test.compile(state, settingsValue, name, index, origin.getOrElse(state.typeId))
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

    def getGroupIndex(implicit groupConfig: GroupConfig): Either[String, GroupIndex] = {
      val group = settings.map(_.group).getOrElse(0)
      GroupIndex.from(group).toRight(s"Invalid group setting $group in test $name")
    }
  }

  final case class CompiledUnitTests[Ctx <: StatelessContext](
      tests: AVector[CompiledUnitTest[Ctx]],
      errorCodes: Map[Int, Option[SourceIndex]]
  ) {
    def getError(
        testName: String,
        errorCode: Option[Int],
        detail: String,
        debugMessages: Option[String]
    ): Compiler.Error = {
      val message     = s"Test failed: $testName, detail: $detail"
      val sourceIndex = errorCode.flatMap(errorCodes.get).flatten
      CompilerError.TestError(message, sourceIndex, debugMessages)
    }
  }

  trait State[Ctx <: StatelessContext] { _: Compiler.State[Ctx] =>
    private var _isInTestContext: Boolean                                 = false
    private val testCheckCalls: mutable.HashMap[Int, Option[SourceIndex]] = mutable.HashMap.empty

    def isInTestContext: Boolean = _isInTestContext
    private def withinTestContext[T](func: => T): T = {
      _isInTestContext = true
      val result = func
      _isInTestContext = false
      result
    }

    @scala.annotation.tailrec
    private def nextErrorCode: Int = {
      val errorCode = Random.between(0, Int.MaxValue)
      if (testCheckCalls.contains(errorCode)) nextErrorCode else errorCode
    }

    def addTestCheckCall(ast: Ast.Positioned): Int = {
      val errorCode = nextErrorCode
      testCheckCalls.addOne(errorCode -> ast.sourceIndex)
      errorCode
    }

    def genUnitTestCode(unitTestDefs: Seq[UnitTestDef[Ctx]]): CompiledUnitTests[Ctx] = {
      Ast.UniqueDef.checkDuplicates(unitTestDefs, "tests")
      withinTestContext {
        val tests      = AVector.from(unitTestDefs.flatMap(_.compile(this)))
        val errorCodes = testCheckCalls.toMap
        testCheckCalls.clear()
        CompiledUnitTests(tests, errorCodes)
      }
    }
  }
}
