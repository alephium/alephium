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

import akka.util.ByteString

import org.alephium.io.IOResult
import org.alephium.protocol.config.{ConsensusConfigs, GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{BlockHash => _, _}
import org.alephium.ralph.error.CompilerError
import org.alephium.util.{AVector, Hex, I256, TimeStamp, U256}

// scalastyle:off number.of.methods file.size.limit
object Testing {
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
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
      case expr if expr.getType(state).map(_.toVal) == Seq(tpe) =>
        state.calcConstant(expr).asInstanceOf[V]
      case _ =>
        throw Compiler.Error(
          s"Invalid $name expr, expected a constant expr of type $tpe",
          expr.sourceIndex
        )
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
              .mkString("[", ", ", "]")}",
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

  private def getContract[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      typeId: Ast.TypeId
  ): Ast.Contract = {
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

  private def getArrayValues(
      baseName: String,
      tpe: Type.FixedSizeArray,
      elements: Seq[(String, Val, Boolean)]
  ) = {
    Seq
      .tabulate(tpe.getArraySize) { index =>
        elements.map { case (name, value, isMutable) =>
          (s"$baseName[$index]$name", value, isMutable)
        }
      }
      .flatten
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def genDefaultValue[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      name: String,
      tpe: Type,
      isMutable: Boolean
  ): Seq[(String, Val, Boolean)] = {
    state.resolveType(tpe) match {
      case Type.Struct(id) =>
        state
          .getStruct(id)
          .fields
          .flatMap { field =>
            val valName = s"$name.${field.name}"
            genDefaultValue(state, valName, field.tpe, isMutable && field.isMutable)
          }
      case t @ Type.FixedSizeArray(baseType, _) =>
        val values = genDefaultValue(state, "", baseType, isMutable)
        getArrayValues(name, t, values)
      case _: Type.Contract => Seq((name, Val.ByteVec(ContractId.zero.bytes), isMutable))
      case tpe: Type if tpe.isPrimitive => Seq((name, tpe.toVal.default, isMutable))
      case tpe => throw Compiler.Error(s"Invalid type $tpe", tpe.sourceIndex)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def getContractFields(contract: Ast.Contract, fields: Seq[(String, Val, Boolean)]) = {
    val allFields = if (contract.hasStdIdField) {
      fields :+ (Ast.stdArg.ident.name, contract.stdInterfaceId.get, Ast.stdArg.isMutable)
    } else {
      fields
    }
    val (immFields, mutFields) = allFields.view.partition(!_._3)
    (AVector.from(immFields.map(f => (f._1, f._2))), AVector.from(mutFields.map(f => (f._1, f._2))))
  }

  final case class CreateContractDef[Ctx <: StatelessContext](
      typeId: Ast.TypeId,
      tokens: Seq[(Ast.Expr[Ctx], Ast.Expr[Ctx])],
      fields: Seq[Ast.Expr[Ctx]],
      address: Option[Ast.Ident]
  ) extends Ast.Positioned {
    def isSelfType: Boolean = typeId.name == "Self"

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
        name: String,
        expr: Ast.Expr[Ctx],
        tpe: Type,
        isMutable: Boolean
    ): Seq[(String, Val, Boolean)] = {
      (state.resolveType(tpe), expr) match {
        case (t: Type.FixedSizeArray, Ast.CreateArrayExpr1(elements)) =>
          elements.zipWithIndex.flatMap { case (expr, index) =>
            getValues(state, s"$name[$index]", expr, t.baseType, isMutable)
          }
        case (t: Type.FixedSizeArray, Ast.CreateArrayExpr2(element, _)) =>
          val elements = getValues(state, "", element, t.baseType, isMutable)
          getArrayValues(name, t, elements)
        case (t: Type.Struct, Ast.StructCtor(_, fields)) =>
          val struct = state.getStruct(t.id)
          struct.fields.flatMap { structField =>
            val fieldDef = fields.find(_._1 == structField.ident)
            fieldDef.flatMap(_._2) match {
              case Some(expr) =>
                val valName = s"$name.${structField.name}"
                getValues(state, valName, expr, structField.tpe, isMutable && structField.isMutable)
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
          Seq((name, value, isMutable))
        case (t: Type.Contract, expr @ Ast.Variable(_)) =>
          val value = checkAndGetValue[Ctx, Val.ByteVec](state, expr, t.toVal, "contract field")
          Seq((name, value, isMutable))
        case (t: Type, expr) if t.isPrimitive =>
          val value = checkAndGetValue[Ctx, Val](state, expr, t.toVal, "contract field")
          Seq((name, value, isMutable))
        case (t, expr) =>
          throw Compiler.Error(
            s"Invalid expression, expected a constant expression of type $t",
            expr.sourceIndex
          )
      }
    }

    private def getFields(
        state: Compiler.State[Ctx],
        origin: Ast.Contract,
        self: Ast.Contract
    ): (AVector[(String, Val)], AVector[(String, Val)]) = {
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
          getValues(state, argument.ident.name, fieldExpr, fieldType, argument.isMutable)
        } else {
          genDefaultValue(state, argument.ident.name, fieldType, argument.isMutable)
        }
      }
      getContractFields(self, values)
    }

    private def getTypeIdAndFields(state: Compiler.State[Ctx], origin: Ast.TypeId) = {
      if (isSelfType) {
        val selfTypeId   = state.typeId
        val selfContract = getContract(state, selfTypeId)
        val originContract =
          if (selfTypeId == origin) selfContract else getAbstractContract(state, origin)
        val fields = getFields(state, originContract, selfContract)
        (selfTypeId, fields._1, fields._2)
      } else {
        val contract = getContract(state, typeId)
        val fields   = getFields(state, contract, contract)
        (typeId, fields._1, fields._2)
      }
    }

    private def getTokenAmount(state: Compiler.State[Ctx]): AVector[(TokenId, U256)] = {
      val tokenAmounts = mutable.Map.empty[TokenId, U256]
      tokens.foreach { case (tokenIdExpr, tokenAmountExpr) =>
        val (tokenId, tokenAmount) = getApprovedToken(state, tokenIdExpr, tokenAmountExpr)
        tokenAmounts.get(tokenId) match {
          case None => tokenAmounts(tokenId) = tokenAmount
          case Some(amount) =>
            tokenAmounts(tokenId) = amount
              .add(tokenAmount)
              .getOrElse(
                throw Compiler.Error(
                  s"Token ${tokenId.toHexString} amount overflow",
                  tokenAmountExpr.sourceIndex
                )
              )
        }
      }
      AVector.from(tokenAmounts)
    }

    def compileBeforeContract(
        state: Compiler.State[Ctx],
        origin: Ast.TypeId
    ): CreateContractValue = {
      val tokenAmounts                   = getTokenAmount(state)
      val (typeId, immFields, mutFields) = getTypeIdAndFields(state, origin)
      val contractId                     = ContractId.random
      address.foreach(
        state.addTestingConstant(_, Val.ByteVec(contractId.bytes), Type.Contract(typeId))
      )
      CreateContractValue(typeId, tokenAmounts, immFields, mutFields, contractId)
    }

    def compileAfterContract(
        state: Compiler.State[Ctx],
        origin: Ast.TypeId,
        selfContractId: ContractId
    ): CreateContractValue = {
      val tokenAmounts                   = getTokenAmount(state)
      val (typeId, immFields, mutFields) = getTypeIdAndFields(state, origin)
      val contractId = address match {
        case Some(ident) =>
          val contractId = state.getConstantValue(ident) match {
            case Val.ByteVec(bytes) => ContractId.from(bytes)
            case _                  => None
          }
          contractId.getOrElse(
            throw Compiler.Error(s"Invalid contract id in after def", sourceIndex)
          )
        case None =>
          if (isSelfType) {
            selfContractId
          } else {
            throw Compiler.Error(s"Expect a contract id in after def", sourceIndex)
          }
      }
      CreateContractValue(typeId, tokenAmounts, immFields, mutFields, contractId)
    }
  }
  final case class CreateContractValue(
      typeId: Ast.TypeId,
      tokens: AVector[(TokenId, U256)],
      immFields: AVector[(String, Val)],
      mutFields: AVector[(String, Val)],
      contractId: ContractId
  )

  final case class CreateContractDefs[Ctx <: StatelessContext](defs: Seq[CreateContractDef[Ctx]])
      extends Ast.Positioned
  object CreateContractDefs {
    lazy val empty: CreateContractDefs[StatefulContext] = CreateContractDefs(Seq.empty)
  }

  final case class SingleTestDef[Ctx <: StatelessContext](
      before: CreateContractDefs[Ctx],
      after: CreateContractDefs[Ctx],
      assets: Option[ApprovedAssetsDef[Ctx]],
      body: Seq[Ast.Statement[Ctx]]
  ) extends Ast.Positioned {
    private def getBeforeContracts(
        state: Compiler.State[Ctx],
        origin: Ast.TypeId
    ): AVector[CreateContractValue] = {
      val (selfContracts, dependencies) = before.defs.partition(_.isSelfType)
      if (selfContracts.length > 1) {
        throw Compiler.Error(s"Self contract is defined multiple times", sourceIndex)
      }
      if (selfContracts.isEmpty) {
        val contract = getContract(state, state.typeId)
        val values = contract.fields.flatMap { argument =>
          genDefaultValue(state, argument.ident.name, argument.tpe, argument.isMutable)
        }
        val (immFields, mutFields) = getContractFields(contract, values)
        val selfContract =
          CreateContractValue(state.typeId, AVector.empty, immFields, mutFields, ContractId.random)
        AVector.from(dependencies.map(_.compileBeforeContract(state, origin))) :+ selfContract
      } else {
        AVector.from(dependencies ++ selfContracts).map(_.compileBeforeContract(state, origin))
      }
    }

    private def getAfterContracts(
        selfContractId: ContractId,
        state: Compiler.State[Ctx],
        origin: Ast.TypeId
    ): AVector[CreateContractValue] = {
      val (selfContracts, dependencies) = after.defs.partition(_.isSelfType)
      if (selfContracts.length > 1) {
        throw Compiler.Error(s"Self contract is defined multiple times", sourceIndex)
      }
      AVector
        .from(dependencies ++ selfContracts)
        .map(_.compileAfterContract(state, origin, selfContractId))
    }

    def compile(
        state: Compiler.State[Ctx],
        settings: Option[SettingsValue],
        name: String,
        index: Int,
        origin: Ast.TypeId
    ): CompiledUnitTest[Ctx] = {
      val scopeId = Ast.FuncId(s"$name:$index", false)
      state.setFuncScope(scopeId)
      state.withScope(this) {
        val beforeContracts = getBeforeContracts(state, origin)
        val afterContracts  = getAfterContracts(beforeContracts.last.contractId, state, origin)
        val compiledAssets  = assets.map(_.compile(state))
        body.foreach(_.check(state))
        val method = Method[Ctx](
          isPublic = true,
          usePreapprovedAssets = assets.isDefined,
          useContractAssets = false,
          usePayToContractOnly = false,
          useRoutePattern = false,
          argsLength = 0,
          localsLength = state.getLocalVarSize(scopeId),
          returnLength = 0,
          instrs = AVector.from(body.flatMap(_.genCode(state)))
        )
        CompiledUnitTest(
          name,
          this,
          settings,
          beforeContracts,
          afterContracts,
          compiledAssets,
          method
        )
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
      ast: SingleTestDef[Ctx],
      settings: Option[SettingsValue],
      before: AVector[CreateContractValue],
      after: AVector[CreateContractValue],
      assets: Option[ApprovedAssetsValue],
      method: Method[Ctx]
  ) {
    lazy val selfContract: CreateContractValue = before.last

    def getGroupIndex(implicit groupConfig: GroupConfig): Either[String, GroupIndex] = {
      val group = settings.map(_.group).getOrElse(0)
      GroupIndex.from(group).toRight(s"Invalid group setting $group in test $name")
    }

    def getInputAssets(): AVector[AssetOutput] = {
      assets
        .map(_.assets.map { case (lockupScript, tokens) =>
          val attoAlphAmount =
            tokens.find(_._1 == TokenId.alph).map(_._2).getOrElse(dustUtxoAmount)
          val remains = tokens.filter(_._1 != TokenId.alph)
          AssetOutput(
            attoAlphAmount,
            lockupScript,
            TimeStamp.zero,
            remains,
            ByteString.empty
          )
        })
        .getOrElse(AVector.empty)
    }
  }

  private def checkFields(
      expected: AVector[(String, Val)],
      have: AVector[Val]
  ) = {
    expected.foreachWithIndexE { case ((name, expectedVal), index) =>
      val haveVal = have(index)
      if (expectedVal != haveVal) {
        Left(s"invalid field $name, expected $expectedVal, have: $haveVal")
      } else {
        Right(())
      }
    }
  }

  private def checkTokens(
      expected: AVector[(TokenId, U256)],
      have: ContractOutput
  ) = {
    expected.foreachE { case (tokenId, expectedAmount) =>
      val amount = if (tokenId == TokenId.alph) {
        have.amount
      } else {
        have.tokens.view.find(_._1 == tokenId).map(_._2).getOrElse(U256.Zero)
      }
      if (amount != expectedAmount) {
        Left(
          s"invalid token amount, token id: ${tokenId.toHexString}, expected: $expectedAmount, have: $amount"
        )
      } else {
        Right(())
      }
    }
  }

  def checkContractState(
      expected: CreateContractValue,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      asset: ContractOutput
  ): Either[String, Unit] = {
    for {
      _ <- checkFields(expected.immFields, immFields)
      _ <- checkFields(expected.mutFields, mutFields)
      _ <- checkTokens(expected.tokens, asset)
    } yield ()
  }

  final case class CompiledUnitTests[Ctx <: StatelessContext](
      tests: AVector[CompiledUnitTest[Ctx]],
      sourceIndexes: Map[Int, Option[SourceIndex]]
  ) {
    def getError(
        testName: String,
        sourcePosIndex: Option[Int],
        detail: String,
        debugMessages: String
    ): Compiler.Error = {
      val sourceIndex = sourcePosIndex.flatMap(sourceIndexes.get).flatten
      getTestError(testName, sourceIndex, detail, debugMessages)
    }
  }

  def getTestError(
      testName: String,
      sourceIndex: Option[SourceIndex],
      detail: String,
      debugMessages: String
  ): Compiler.Error = {
    val message = s"Test failed: $testName, detail: $detail"
    val msg     = Option.when(debugMessages.nonEmpty)(debugMessages)
    CompilerError.TestError(message, sourceIndex, msg)
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
    private def nextSourcePosIndex: Int = {
      val sourcePosIndex = Random.between(0, Int.MaxValue)
      if (testCheckCalls.contains(sourcePosIndex)) nextSourcePosIndex else sourcePosIndex
    }

    def addTestCheckCall(ast: Ast.Positioned): Int = {
      val errorCode = nextSourcePosIndex
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

  // scalastyle:off method.length
  def run(
      createWorldState: GroupIndex => IOResult[WorldState.Staging],
      sourceCode: String,
      contracts: AVector[CompiledContract]
  )(implicit
      consensusConfigs: ConsensusConfigs,
      networkConfig: NetworkConfig,
      logConfig: LogConfig,
      groupConfig: GroupConfig
  ): Either[String, Unit] = {
    val contractMap = contracts.map(c => (c.ast.ident.name, c)).iterator.toMap
    contracts.foreachE { testingContract =>
      testingContract.tests match {
        case Some(tests) =>
          tests.tests.foreachE { test =>
            for {
              groupIndex <- test.getGroupIndex
              worldState <- from(createWorldState(groupIndex))
              blockHash      = test.settings.flatMap(_.blockHash).getOrElse(BlockHash.random)
              blockTimeStamp = test.settings.flatMap(_.blockTimeStamp).getOrElse(TimeStamp.now())
              txId           = TransactionId.random
              _ <- createContracts(
                worldState,
                contractMap,
                testingContract,
                test,
                blockHash,
                txId
              )
              blockEnv    = BlockEnv.mockup(groupIndex, blockHash, blockTimeStamp)
              testGasFee  = nonCoinbaseMinGasPrice * maximalGasPerTx
              inputAssets = test.getInputAssets()
              txEnv       = TxEnv.mockup(txId, inputAssets)
              context     = StatefulContext(blockEnv, txEnv, worldState, maximalGasPerTx)
              _ <- ContractRunner.run(
                context,
                test.selfContract.contractId,
                None,
                inputAssets,
                testingContract.debugCode.methodsLength,
                AVector.empty,
                test.method,
                testGasFee
              ) match {
                case Right(_) => Right(())
                case Left(Left(error)) =>
                  Left(s"IO error occurred when running test `${test.name}`: $error")
                case Left(Right(error)) =>
                  val debugMessages = extractDebugMessage(worldState, test)
                  Left(getUnitTestError(sourceCode, tests, test.name, error, debugMessages))
              }
              _ <- checkStateAfterTesting(worldState, sourceCode, test)
            } yield ()
          }
        case None => Right(())
      }
    }
  }
  // scalastyle:off method.length

  private def createContracts(
      worldState: WorldState.Staging,
      allContracts: Map[String, CompiledContract],
      testingContract: CompiledContract,
      test: Testing.CompiledUnitTest[StatefulContext],
      blockHash: BlockHash,
      txId: TransactionId
  ) = {
    test.before.foreachE { c =>
      val contractCode = if (c.typeId == testingContract.ast.ident) {
        val code = testingContract.debugCode
        code.copy(methods = code.methods :+ test.method)
      } else {
        allContracts(c.typeId.name).debugCode
      }
      val attoAlphAmount =
        c.tokens.find(_._1 == TokenId.alph).map(_._2).getOrElse(minimalAlphInContract)
      val remains = c.tokens.filter(_._1 != TokenId.alph)
      val output  = ContractOutput(attoAlphAmount, LockupScript.p2c(c.contractId), remains)
      ContractRunner
        .createContractUnsafe(
          worldState,
          c.contractId,
          contractCode,
          c.immFields.map(_._2),
          c.mutFields.map(_._2),
          output,
          blockHash,
          txId
        )
        .left
        .map(err => s"IO error occurred when creating contract: $err")
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  private def getUnitTestError(
      sourceCode: String,
      tests: CompiledUnitTests[StatefulContext],
      testName: String,
      exeFailure: ExeFailure,
      debugMessages: String
  ): String = {
    val (sourcePosIndex, msg) = exeFailure match {
      case AssertionFailedWithErrorCode(_, sourcePosIndex) =>
        (Some(sourcePosIndex), s"Assertion Failed in test `$testName`")
      case ExpectedAnExeFailure(sourcePosIndex) => (Some(sourcePosIndex), exeFailure.toString)
      case NotEqualInTest(_, _, sourcePosIndex) => (Some(sourcePosIndex), exeFailure.toString)
      case NotExpectedErrorInTest(_, _, sourcePosIndex) =>
        (Some(sourcePosIndex), exeFailure.toString)
      case _ => (None, exeFailure.toString)
    }
    val detail = s"VM execution error: $msg"
    tests.getError(testName, sourcePosIndex, detail, debugMessages).format(sourceCode)
  }

  private def extractDebugMessage(
      worldState: WorldState.Staging,
      unitTest: CompiledUnitTest[StatefulContext]
  ): String = {
    val allEvents     = worldState.nodeIndexesState.logState.getNewLogs()
    val debugMessages = mutable.ArrayBuffer.empty[String]
    allEvents.foreach { event =>
      unitTest.before.find(_.contractId == event.contractId).foreach { contract =>
        event.states.foreach { state =>
          if (I256.from(state.index.toInt) == debugEventIndex.v) {
            state.fields.headOption match {
              case Some(value: Val.ByteVec) =>
                val message = s"> Contract @ ${contract.typeId.name} - ${value.bytes.utf8String}"
                debugMessages.addOne(message)
              case _ => ()
            }
          }
        }
      }
    }
    if (debugMessages.isEmpty) "" else debugMessages.mkString("", "\n", "\n")
  }

  private def from[T](result: IOResult[T]): Either[String, T] = {
    result.left.map(error => s"Failed in IO: $error")
  }

  private def checkStateAfterTesting(
      worldState: WorldState.Staging,
      sourceCode: String,
      test: CompiledUnitTest[StatefulContext]
  ): Either[String, Unit] = {
    test.after.foreachWithIndexE { case (contract, index) =>
      for {
        state <- from(worldState.getContractState(contract.contractId))
        asset <- from(worldState.getContractAsset(contract.contractId))
        _ <- checkContractState(contract, state.immFields, state.mutFields, asset).left
          .map { detail =>
            val sourceIndex  = test.ast.after.defs(index).sourceIndex
            val debugMessage = extractDebugMessage(worldState, test)
            val error        = getTestError(test.name, sourceIndex, detail, debugMessage)
            error.format(sourceCode)
          }
      } yield ()
    }
  }
}
