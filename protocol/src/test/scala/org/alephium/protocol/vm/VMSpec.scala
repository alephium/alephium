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

package org.alephium.protocol.vm

import scala.collection.mutable

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.protocol.{Hash, Signature, SignatureSchema}
import org.alephium.protocol.config.NetworkConfigFixture
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util._

class VMSpec extends AlephiumSpec with ContextGenerators with NetworkConfigFixture.Default {
  trait BaseFixture[Ctx <: StatelessContext] {
    val baseMethod = Method[Ctx](
      isPublic = true,
      usePreapprovedAssets = false,
      useContractAssets = false,
      argsLength = 0,
      localsLength = 0,
      returnLength = 0,
      instrs = AVector.empty
    )

    def test0(instrs: AVector[Instr[Ctx]], expected: ExeResult[Unit]) = {
      test1(baseMethod.copy(instrs = instrs), expected)
    }

    def test1(method: Method[Ctx], expected: ExeResult[Unit]) = {
      test2(AVector(method), expected)
    }

    def test2(methods: AVector[Method[Ctx]], expected: ExeResult[Unit]): Unit
  }

  trait StatelessFixture extends BaseFixture[StatelessContext] {
    def test2(methods: AVector[Method[StatelessContext]], expected: ExeResult[Unit]) = {
      test3(StatelessScript.unsafe(methods), expected)
    }

    def test3(script: StatelessScript, expected: ExeResult[Unit]): Unit = {
      val (obj, context) = prepareStatelessScript(script)
      StatelessVM.execute(context, obj, AVector.empty).map(_ => ()) is expected
      ()
    }
  }

  it should "check the entry method of stateless scripts" in new StatelessFixture {
    test1(baseMethod, okay)
    test1(baseMethod.copy(usePreapprovedAssets = true), failed(ExpectNonPayableMethod))
    test1(baseMethod.copy(argsLength = -1), failed(InvalidMethodArgLength(0, -1)))
    intercept[AssertionError](
      test1(baseMethod.copy(localsLength = -1), okay)
    ).getMessage is
      "assumption failed"
    test1(baseMethod.copy(returnLength = -1), failed(NegativeArgumentInStack))
  }

  trait StatefulFixture extends BaseFixture[StatefulContext] {
    def test2(methods: AVector[Method[StatefulContext]], expected: ExeResult[Unit]) = {
      test3(StatefulScript.unsafe(methods), expected)
    }

    def test3(script: StatefulScript, expected: ExeResult[Unit]): Unit = {
      val (obj, context) = prepareStatefulScript(script)
      val argsLength     = obj.code.getMethod(0).rightValue.argsLength
      val args =
        if (argsLength <= 0) AVector.empty[Val] else AVector.fill[Val](argsLength)(Val.True)
      StatefulVM.execute(context, obj, args).map(_ => ()) is expected
      ()
    }
  }

  it should "check the entry method of stateful scripts" in new StatefulFixture {
    test1(baseMethod, okay)
    test1(baseMethod.copy(usePreapprovedAssets = true), failed(InvalidBalances))
    test1(baseMethod.copy(argsLength = -1), failed(InvalidMethodArgLength(0, -1)))
    intercept[AssertionError](
      test1(baseMethod.copy(localsLength = -1), okay)
    ).getMessage is
      "assumption failed"
    test1(baseMethod.copy(returnLength = -1), failed(NegativeArgumentInStack))
    intercept[AssertionError](
      test1(baseMethod.copy(argsLength = 2, localsLength = 1), okay)
    ).getMessage is
      "assumption failed"
  }

  trait Fixture {
    val baseMethod = Method[StatefulContext](
      isPublic = true,
      usePreapprovedAssets = false,
      useContractAssets = false,
      argsLength = 0,
      localsLength = 0,
      returnLength = 0,
      instrs = AVector.empty
    )

    def failMainMethod(
        method: Method[StatefulContext],
        args: AVector[Val] = AVector.empty,
        gasLimit: GasBox = minimalGas,
        failure: ExeFailure
    ): Assertion = {
      val contract = StatefulContract(0, methods = AVector(method))
      failContract(contract, args, gasLimit, failure)
    }

    def failContract(
        contract: StatefulContract,
        args: AVector[Val] = AVector.empty,
        gasLimit: GasBox = minimalGas,
        failure: ExeFailure
    ): Assertion = {
      val (obj, context) =
        prepareContract(contract, AVector[Val](), gasLimit)
      StatefulVM.execute(context, obj, args).leftValue.rightValue is failure
    }
  }

  it should "not call from private function" in new Fixture {
    failMainMethod(baseMethod.copy(isPublic = false), failure = ExternalPrivateMethodCall)
  }

  it should "fail when there is no main method" in new Fixture {
    failContract(
      StatefulContract(0, AVector.empty),
      failure = InvalidMethodIndex(0)
    )
  }

  it should "check the number of args for entry method" in new Fixture {
    failMainMethod(baseMethod.copy(argsLength = 1), failure = InvalidMethodArgLength(0, 1))
  }

  it should "check the number of args for called method" in new Fixture {
    failContract(
      StatefulContract(
        0,
        AVector(baseMethod.copy(instrs = AVector(CallLocal(1))), baseMethod.copy(argsLength = 1))
      ),
      failure = InsufficientArgs
    )
  }

  it should "not return values for main function" in new Fixture {
    failMainMethod(
      baseMethod.copy(returnLength = 1, instrs = AVector(U256Const0, Return)),
      failure = NonEmptyReturnForMainFunction
    )
  }

  it should "overflow oprand stack" in new Fixture {
    val method =
      Method[StatefulContext](
        isPublic = true,
        usePreapprovedAssets = false,
        useContractAssets = false,
        argsLength = 1,
        localsLength = 1,
        returnLength = 0,
        instrs = AVector(
          U256Const0,
          U256Const0,
          LoadLocal(0),
          U256Const0,
          U256Gt,
          IfFalse(4),
          LoadLocal(0),
          U256Const1,
          U256Sub,
          CallLocal(0)
        )
      )

    failMainMethod(
      method,
      AVector(Val.U256(U256.unsafe(opStackMaxSize.toLong / 2 - 1))),
      1000000,
      StackOverflow
    )
  }

  it should "execute the following script" in {
    val method =
      Method[StatefulContext](
        isPublic = true,
        usePreapprovedAssets = false,
        useContractAssets = false,
        argsLength = 1,
        localsLength = 1,
        returnLength = 1,
        instrs = AVector(LoadLocal(0), LoadField(1), U256Add, U256Const5, U256Add, Return)
      )
    val contract = StatefulContract(2, methods = AVector(method))
    val (obj, context) =
      prepareContract(contract, AVector[Val](Val.U256(U256.Zero), Val.U256(U256.One)))
    StatefulVM.executeWithOutputs(context, obj, AVector(Val.U256(U256.Two))) isE
      AVector[Val](Val.U256(U256.unsafe(8)))
  }

  it should "call method" in {
    val method0 = Method[StatelessContext](
      isPublic = true,
      usePreapprovedAssets = false,
      useContractAssets = false,
      argsLength = 1,
      localsLength = 1,
      returnLength = 1,
      instrs = AVector(LoadLocal(0), CallLocal(1), Return)
    )
    val method1 =
      Method[StatelessContext](
        isPublic = false,
        usePreapprovedAssets = false,
        useContractAssets = false,
        argsLength = 1,
        localsLength = 1,
        returnLength = 1,
        instrs = AVector(LoadLocal(0), U256Const1, U256Add, Return)
      )
    val script = StatelessScript.unsafe(AVector(method0, method1))
    val obj    = script.toObject
    StatelessVM.executeWithOutputs(genStatelessContext(), obj, AVector(Val.U256(U256.Two))) isE
      AVector[Val](Val.U256(U256.unsafe(3)))
  }

  trait BalancesFixture {
    val (_, pubKey0) = SignatureSchema.generatePriPub()
    val address0     = Val.Address(LockupScript.p2pkh(pubKey0))
    val balances0    = MutBalancesPerLockup(100, mutable.Map.empty, 0)
    val (_, pubKey1) = SignatureSchema.generatePriPub()
    val address1     = Val.Address(LockupScript.p2pkh(pubKey1))
    val tokenId      = Hash.random
    val balances1    = MutBalancesPerLockup(1, mutable.Map(tokenId -> 99), 0)

    def mockContext(): StatefulContext =
      new StatefulContext with NetworkConfigFixture.Default {
        val worldState: WorldState.Staging = cachedWorldState.staging()
        def blockEnv: BlockEnv             = genBlockEnv()
        def txEnv: TxEnv                   = genTxEnv(None, AVector.empty)
        override def txId: Hash            = Hash.zero
        var gasRemaining                   = GasBox.unsafe(100000)
        def nextOutputIndex: Int           = 0
        def logConfig: LogConfig           = LogConfig.allEnabled()

        def getInitialBalances(): ExeResult[MutBalances] = {
          Right(
            MutBalances(
              mutable.ArrayBuffer(
                address0.lockupScript -> balances0,
                address1.lockupScript -> balances1
              )
            )
          )
        }

        override val outputBalances: MutBalances = MutBalances.empty
      }

    def testInstrs(
        instrs: AVector[AVector[Instr[StatefulContext]]],
        expected: ExeResult[AVector[Val]]
    ) = {
      val methods = instrs.mapWithIndex { case (instrs, index) =>
        Method[StatefulContext](
          isPublic = index equals 0,
          usePreapprovedAssets = true,
          useContractAssets = false,
          argsLength = 0,
          localsLength = 0,
          returnLength = expected.fold(_ => 0, _.length),
          instrs
        )
      }
      val context = mockContext()
      val obj     = StatefulScript.from(methods).get.toObject

      StatefulVM.executeWithOutputs(context, obj, AVector.empty) is expected

      context
    }

    def pass(instrs: AVector[Instr[StatefulContext]], expected: AVector[Val]) = {
      testInstrs(AVector(instrs), Right(expected))
    }

    def passMulti(
        instrss: AVector[AVector[Instr[StatefulContext]]],
        expected: AVector[Val]
    ) = {
      testInstrs(instrss, Right(expected))
    }

    def fail(instrs: AVector[Instr[StatefulContext]], expected: ExeFailure) = {
      testInstrs(AVector(instrs), failed(expected))
    }
  }

  it should "show remaining balances" in new BalancesFixture {
    val instrs = AVector[Instr[StatefulContext]](
      AddressConst(address0),
      AlphRemaining,
      AddressConst(address1),
      AlphRemaining,
      AddressConst(address1),
      BytesConst(Val.ByteVec(tokenId.bytes)),
      TokenRemaining
    )
    pass(instrs, AVector[Val](Val.U256(100), Val.U256(1), Val.U256(99)))
  }

  it should "fail when there is no token balances" in new BalancesFixture {
    val instrs = AVector[Instr[StatefulContext]](
      AddressConst(address0),
      BytesConst(Val.ByteVec(tokenId.bytes)),
      TokenRemaining
    )
    fail(instrs, NoTokenBalanceForTheAddress)
  }

  it should "approve balances" in new BalancesFixture {
    val instrs = AVector[Instr[StatefulContext]](
      AddressConst(address0),
      U256Const(Val.U256(10)),
      ApproveAlph,
      AddressConst(address0),
      AlphRemaining,
      AddressConst(address1),
      BytesConst(Val.ByteVec(tokenId.bytes)),
      U256Const(Val.U256(10)),
      ApproveToken,
      AddressConst(address1),
      AlphRemaining,
      AddressConst(address1),
      BytesConst(Val.ByteVec(tokenId.bytes)),
      TokenRemaining
    )
    pass(instrs, AVector[Val](Val.U256(90), Val.U256(1), Val.U256(89)))
  }

  it should "pass approved tokens to function call" in new BalancesFixture {
    val instrs0 = AVector[Instr[StatefulContext]](
      AddressConst(address0),
      U256Const(Val.U256(10)),
      ApproveAlph,
      CallLocal(1),
      AddressConst(address0),
      U256Const(Val.U256(20)),
      ApproveAlph,
      CallLocal(2)
    )
    val instrs1 = AVector[Instr[StatefulContext]](
      AddressConst(address0),
      AlphRemaining,
      U256Const(Val.U256(10)),
      U256Eq,
      Assert
    )
    val instrs2 = AVector[Instr[StatefulContext]](
      AddressConst(address0),
      AlphRemaining,
      U256Const(Val.U256(20)),
      U256Eq,
      Assert
    )
    passMulti(AVector(instrs0, instrs1, instrs2), AVector.empty[Val])
  }

  it should "fail when no enough balance for approval" in new BalancesFixture {
    val instrs = AVector[Instr[StatefulContext]](
      AddressConst(address0),
      BytesConst(Val.ByteVec(tokenId.bytes)),
      U256Const(Val.U256(10)),
      ApproveToken
    )
    fail(instrs, NotEnoughBalance)
  }

  it should "transfer asset to output" in new BalancesFixture {
    val instrs = AVector[Instr[StatefulContext]](
      AddressConst(address0),
      AddressConst(address1),
      U256Const(Val.U256(10)),
      TransferAlph,
      AddressConst(address1),
      AddressConst(address0),
      BytesConst(Val.ByteVec(tokenId.bytes)),
      U256Const(Val.U256(1)),
      TransferToken,
      AddressConst(address0),
      AlphRemaining,
      AddressConst(address1),
      AlphRemaining,
      AddressConst(address1),
      BytesConst(Val.ByteVec(tokenId.bytes)),
      TokenRemaining
    )

    val context = pass(instrs, AVector[Val](Val.U256(90), Val.U256(1), Val.U256(98)))
    context.outputBalances.getAttoAlphAmount(address0.lockupScript).get is 90
    context.outputBalances.getAttoAlphAmount(address1.lockupScript).get is 11
    context.outputBalances.getTokenAmount(address0.lockupScript, tokenId).get is 1
    context.outputBalances.getTokenAmount(address1.lockupScript, tokenId).get is 98
  }

  it should "not create invalid contract" in new BalancesFixture {
    def test(contract: StatefulContract, result: Option[ExeFailure]) = {
      val instrs = AVector[Instr[StatefulContext]](
        AddressConst(address0),
        U256Const(Val.U256(10)),
        ApproveAlph,
        BytesConst(Val.ByteVec(serialize(contract))),
        BytesConst(Val.ByteVec(serialize(AVector.empty[Val]))),
        CreateContract
      )
      val expected = result match {
        case Some(failure) => failed(failure)
        case None          => Right(AVector.empty[Val])
      }
      testInstrs(AVector(instrs), expected)
    }

    val method = Method[StatefulContext](
      isPublic = true,
      usePreapprovedAssets = true,
      useContractAssets = false,
      argsLength = 0,
      localsLength = 0,
      returnLength = 0,
      instrs = AVector(Return)
    )
    val contract0 = StatefulContract(0, AVector(method))
    test(contract0, None)

    val contract1 = StatefulContract(0, AVector.empty)
    test(contract1, Some(EmptyMethods))
    val contract2 = StatefulContract(-1, AVector.empty)
    test(contract2, Some(InvalidFieldLength))
  }

  it should "serde instructions" in {
    Instr.statefulInstrs.foreach {
      case Some(instrCompanion: StatefulInstrCompanion0) =>
        deserialize[Instr[StatefulContext]](
          instrCompanion.serialize()
        ).rightValue is instrCompanion
      case _ => ()
    }
  }

  it should "serde script" in {
    val method =
      Method[StatefulContext](
        isPublic = true,
        usePreapprovedAssets = false,
        useContractAssets = false,
        argsLength = 1,
        localsLength = 1,
        returnLength = 0,
        instrs = AVector(LoadLocal(0), LoadField(1), U256Add, U256Const1, U256Add, StoreField(1))
      )
    val contract = StatefulContract(2, methods = AVector(method))
    serialize(contract)(StatefulContract.serde).nonEmpty is true
  }

  it should "check code size" in {
    VM
      .checkCodeSize(minimalGas, ByteString.fromArrayUnsafe(Array.ofDim[Byte](12 * 1024 + 1)))
      .leftValue isE CodeSizeTooLarge
    VM
      .checkCodeSize(
        GasBox.unsafe(200 + 12 * 1024 - 1),
        ByteString.fromArrayUnsafe(Array.ofDim[Byte](12 * 1024))
      )
      .leftValue isE OutOfGas
    VM.checkCodeSize(
      GasBox.unsafe(200 + 12 * 1024),
      ByteString.fromArrayUnsafe(Array.ofDim[Byte](12 * 1024))
    ) isE GasBox.zero
  }

  it should "check field size" in {
    VM.checkFieldSize(
      minimalGas,
      Seq(Val.ByteVec(ByteString.fromArrayUnsafe(Array.ofDim[Byte](3 * 1024 + 1))))
    ).leftValue isE FieldsSizeTooLarge
    VM.checkFieldSize(
      GasBox.unsafe(122),
      Seq(Val.ByteVec(ByteString.fromArrayUnsafe(Array.ofDim[Byte](123))))
    ).leftValue isE OutOfGas
    VM.checkFieldSize(
      GasBox.unsafe(123),
      Seq(Val.ByteVec(ByteString.fromArrayUnsafe(Array.ofDim[Byte](123))))
    ) isE GasBox.zero
  }

  it should "check signature size" in {
    val context0 = genStatefulContext(None)
    StatefulVM.checkRemainingSignatures(context0) isE ()

    val signature = Signature.generate
    val context1  = genStatefulContext(None, signatures = AVector(signature))
    StatefulVM.checkRemainingSignatures(context1).leftValue isE TooManySignatures
  }

  trait NetworkFixture extends ContextGenerators {
    val preLemanContext = genStatefulContext(None)(NetworkConfigFixture.PreLeman)
    val lemanContext    = genStatefulContext(None)(NetworkConfigFixture.Leman)

    val preLemanStatefulVm =
      new StatefulVM(preLemanContext, Stack.ofCapacity(0), Stack.ofCapacity(0))
    val lemanStatefulVm =
      new StatefulVM(lemanContext, Stack.ofCapacity(0), Stack.ofCapacity(0))
  }

  it should "check the minimal contract balance" in new NetworkFixture {
    def genAssetOutput(amount: U256): AssetOutput = {
      TxOutput.asset(
        amount,
        lockupScriptGen.retryUntil(_.isAssetType).sample.get.asInstanceOf[LockupScript.Asset]
      )
    }
    def genContractOutput(amount: U256): ContractOutput = {
      TxOutput.contract(
        amount,
        lockupScriptGen.retryUntil(!_.isAssetType).sample.get.asInstanceOf[LockupScript.P2C]
      )
    }

    val output0 = genAssetOutput(minimalAlphInContract - 1)
    val output1 = genAssetOutput(minimalAlphInContract)
    val output2 = genContractOutput(minimalAlphInContract - 1)
    val output3 = genContractOutput(minimalAlphInContract)

    VM.checkContractAttoAlphAmounts(Seq(output0), HardFork.Mainnet) isE ()
    VM.checkContractAttoAlphAmounts(Seq(output1), HardFork.Mainnet) isE ()
    VM.checkContractAttoAlphAmounts(Seq(output2), HardFork.Mainnet) isE ()
    VM.checkContractAttoAlphAmounts(Seq(output3), HardFork.Mainnet) isE ()

    VM.checkContractAttoAlphAmounts(Seq(output0), HardFork.Leman) isE ()
    VM.checkContractAttoAlphAmounts(Seq(output1), HardFork.Leman) isE ()
    VM.checkContractAttoAlphAmounts(Seq(output2), HardFork.Leman).leftValue isE
      LowerThanContractMinimalBalance
    VM.checkContractAttoAlphAmounts(Seq(output3), HardFork.Leman) isE ()
  }

  it should "check method modifier compatibility" in new NetworkFixture {
    val contract0 = StatefulContract(0, AVector(Method(true, true, true, 0, 0, 0, AVector.empty)))
    val contract1 = StatefulContract(0, AVector(Method(true, false, false, 0, 0, 0, AVector.empty)))
    val contract2 = StatefulContract(0, AVector(Method(true, true, false, 0, 0, 0, AVector.empty)))
    val contract3 = StatefulContract(0, AVector(Method(true, false, true, 0, 0, 0, AVector.empty)))

    def test(vm: StatefulVM, contract: StatefulContract, succeeded: Boolean) = {
      val obj = StatefulContractObject.from(contract, AVector.empty, ContractId.random)
      if (succeeded) {
        vm.execute(obj, 0, AVector.empty) match {
          case Right(res)  => res is ()
          case Left(error) => error isnotE InvalidMethodModifierBeforeLeman
        }
      } else {
        vm.execute(obj, 0, AVector.empty).leftValue isE InvalidMethodModifierBeforeLeman
      }
    }

    test(lemanStatefulVm, contract0, true)
    test(lemanStatefulVm, contract1, true)
    test(lemanStatefulVm, contract2, true)
    test(lemanStatefulVm, contract3, true)
    test(preLemanStatefulVm, contract0, true)
    test(preLemanStatefulVm, contract1, true)
    test(preLemanStatefulVm, contract2, false)
    test(preLemanStatefulVm, contract3, false)
  }

  it should "preserve stack safety" in new StatefulFixture {
    {
      info("No local variables")
      val method0 =
        Method[StatefulContext](true, false, false, 0, 0, 0, AVector(ConstTrue, CallLocal(1)))
      val method1 = Method[StatefulContext](true, false, false, 0, 0, 0, AVector(Pop))

      test3(StatefulScript.unsafe(AVector(method0, method1)), failed(StackUnderflow))
    }

    {
      info("Non-empty local variables")

      val method0 =
        Method[StatefulContext](true, false, false, 0, 1, 0, AVector(ConstTrue, CallLocal(1)))
      val method1 = Method[StatefulContext](true, false, false, 0, 0, 0, AVector(Pop))

      test3(StatefulScript.unsafe(AVector(method0, method1)), failed(StackUnderflow))
    }
  }
}
