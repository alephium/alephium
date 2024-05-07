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

import scala.collection.mutable.ArrayBuffer

import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import scala.reflect.ClassTag

import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{NetworkConfig, NetworkConfigFixture}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.ContractPool.ContractAssetInUsing
import org.alephium.util.{AlephiumSpec, AVector}

class FrameSpec extends AlephiumSpec with FrameFixture {
  it should "initialize frame and use operand stack for method args" in {
    val frame = genStatelessFrame()
    frame.opStack.offset is 3
    frame.opStack.capacity is 0
    frame.opStack.currentIndex is 3
  }

  it should "create new frame and use operand stack for method args" in {
    val frame = genStatefulFrame()
    frame.opStack.offset is 2
    frame.opStack.capacity is 8
    frame.opStack.currentIndex is 2
  }

  it should "popOpStackBool" in new PopOpStackFixture {
    test[Val.Bool](_.popOpStackBool())
  }

  it should "popOpStackI256" in new PopOpStackFixture {
    test[Val.I256](_.popOpStackI256())
  }

  it should "popOpStackU256" in new PopOpStackFixture {
    test[Val.U256](_.popOpStackU256())
  }

  it should "popOpStackByteVec" in new PopOpStackFixture {
    test[Val.ByteVec](_.popOpStackByteVec())
  }

  it should "popOpStackAddress" in new PopOpStackFixture {
    test[Val.Address](_.popOpStackAddress())
  }

  trait FrameBalanceFixture {
    def prepareContract() = {
      val (contractId, code, _, mutFields, contractOutputRef, contractOutput) =
        generateContract().sample.get
      cachedWorldState.createContractLegacyUnsafe(
        contractId,
        code,
        mutFields,
        contractOutputRef,
        contractOutput
      )
      contractId
    }

    val from = lockupScriptGen.sample.get
    def balanceState =
      MutBalanceState(
        MutBalances.empty,
        MutBalances(ArrayBuffer(from -> MutBalancesPerLockup.alph(ALPH.alph(1000))))
      )
    def preLemanFrame = {
      genStatefulFrame(Some(balanceState))(NetworkConfigFixture.Genesis)
    }
    def lemanFrame = {
      val balanceState =
        MutBalanceState(
          MutBalances.empty,
          MutBalances(ArrayBuffer(from -> MutBalancesPerLockup.alph(ALPH.alph(1000))))
        )
      genStatefulFrame(Some(balanceState))(NetworkConfigFixture.Leman)
    }
    def rhoneFrame = {
      val balanceState =
        MutBalanceState(
          MutBalances.empty,
          MutBalances(ArrayBuffer(from -> MutBalancesPerLockup.alph(ALPH.alph(1000))))
        )
      genStatefulFrame(Some(balanceState))(NetworkConfigFixture.Rhone)
    }

    val contract0 =
      StatefulContract(0, AVector(Method(true, true, true, false, 0, 0, 0, AVector.empty)))
    val contract1 =
      StatefulContract(0, AVector(Method(true, false, false, false, 0, 0, 0, AVector.empty)))
    val contract2 =
      StatefulContract(0, AVector(Method(true, true, false, false, 0, 0, 0, AVector.empty)))
    val contract3 =
      StatefulContract(0, AVector(Method(true, false, true, false, 0, 0, 0, AVector.empty)))
    val contract4 =
      StatefulContract(0, AVector(Method(true, true, true, true, 0, 0, 0, AVector.empty)))
    val contract5 =
      StatefulContract(0, AVector(Method(true, false, false, true, 0, 0, 0, AVector.empty)))
    val contract6 =
      StatefulContract(0, AVector(Method(true, true, false, true, 0, 0, 0, AVector.empty)))
    val contract7 =
      StatefulContract(0, AVector(Method(true, false, true, true, 0, 0, 0, AVector.empty)))

    def test(_frame: => StatefulFrame, contract: StatefulContract, emptyOutput: Boolean) = {
      val contractId = prepareContract()
      val method     = contract.methods.head
      val frame      = _frame
      frame.balanceStateOpt.get.approved.all.isEmpty is false

      val result = frame
        .getNewFrameBalancesState(
          StatefulContractObject.from(contract, AVector.empty, AVector.empty, contractId),
          method,
          0
        )

      result.rightValue.isEmpty is emptyOutput
      if (!emptyOutput) {
        if (method.usePreapprovedAssets) {
          frame.balanceStateOpt.get.approved.all.isEmpty is true
        }
        if (method.useContractAssets) {
          frame.ctx.assetStatus(contractId) is a[ContractAssetInUsing]
        }
        if (method.usePayToContractOnly) {
          frame.ctx.assetStatus(contractId) is a[ContractAssetInUsing]
        }
      }
    }

    def assumptionFail[T](test: => T) = {
      intercept[AssertionError](test).getMessage is "assumption failed: Must be true"
    }
  }

  it should "calculate frame balances" in new FrameBalanceFixture {
    test(preLemanFrame, contract0, emptyOutput = false)
    test(preLemanFrame, contract1, emptyOutput = true)
    test(preLemanFrame, contract2, emptyOutput = false)
    test(preLemanFrame, contract3, emptyOutput = true)
    test(preLemanFrame, contract4, emptyOutput = false)
    test(preLemanFrame, contract5, emptyOutput = true)
    test(preLemanFrame, contract6, emptyOutput = false)
    test(preLemanFrame, contract7, emptyOutput = true)

    test(lemanFrame, contract0, emptyOutput = false)
    test(lemanFrame, contract1, emptyOutput = true)
    test(lemanFrame, contract2, emptyOutput = false)
    test(lemanFrame, contract3, emptyOutput = false)
    assumptionFail(test(lemanFrame, contract4, emptyOutput = false))
    assumptionFail(test(lemanFrame, contract5, emptyOutput = true))
    assumptionFail(test(lemanFrame, contract6, emptyOutput = false))
    assumptionFail(test(lemanFrame, contract7, emptyOutput = false))

    test(rhoneFrame, contract0, emptyOutput = false)
    test(rhoneFrame, contract1, emptyOutput = true)
    test(rhoneFrame, contract2, emptyOutput = false)
    test(rhoneFrame, contract3, emptyOutput = false)
    assumptionFail(test(rhoneFrame, contract4, emptyOutput = false))
    test(rhoneFrame, contract5, emptyOutput = false)
    test(rhoneFrame, contract6, emptyOutput = false)
    assumptionFail(test(rhoneFrame, contract7, emptyOutput = true))
  }

  it should "check contract id" in {
    val genesisFrame    = genStatefulFrame()(NetworkConfigFixture.Genesis)
    val sinceLemanFrame = genStatefulFrame()(NetworkConfigFixture.SinceLeman)

    val randomContractId = ContractId.generate
    val zeroContractId   = ContractId.unsafe(TokenId.alph.value)

    genesisFrame.checkContractId(randomContractId).rightValue is ()
    genesisFrame.checkContractId(zeroContractId).rightValue is ()

    sinceLemanFrame.checkContractId(randomContractId).rightValue is ()
    sinceLemanFrame.checkContractId(zeroContractId).leftValue is Right(ZeroContractId)
  }

  it should "test approve contract assets for rhone hardfork" in new FrameFixture {
    val contract = StatefulContract(
      0,
      AVector(
        Method(
          true,
          false,
          useContractAssets = true,
          usePayToContractOnly = false,
          0,
          0,
          0,
          AVector.empty
        ),
        Method(
          true,
          false,
          useContractAssets = false,
          usePayToContractOnly = true,
          0,
          0,
          0,
          AVector.empty
        )
      )
    )

    val contractId     = ContractId.random
    val contractOutput = ContractOutput(ALPH.oneAlph, LockupScript.p2c(contractId), AVector.empty)
    val contractOutputRef = ContractOutputRef.from(TransactionId.random, contractOutput, 0)
    val (contractObj, _) = prepareContract(
      contract,
      AVector.empty,
      AVector.empty,
      contractOutputOpt = Some((contractId, contractOutput, contractOutputRef))
    )(NetworkConfigFixture.Rhone)
    val frame = genStatefulFrame(None)(NetworkConfigFixture.Rhone)

    frame.getNewFrameBalancesState(contractObj, contract.methods.head, 0).rightValue is Some(
      MutBalanceState(
        MutBalances(
          ArrayBuffer(contractOutput.lockupScript -> MutBalancesPerLockup.alph(ALPH.oneAlph))
        ),
        MutBalances.empty
      )
    )
    frame.getNewFrameBalancesState(contractObj, contract.methods.last, 1).rightValue is Some(
      MutBalanceState(MutBalances.empty, MutBalances.empty)
    )
  }

  it should "charge gas for method selector" in {
    val method    = Method[StatefulContext](true, false, false, false, 0, 0, 0, AVector.empty)
    val selector0 = Method.Selector(0)
    val selector1 = Method.Selector(1)
    val methods = AVector(
      method,
      method.copy(instrs = AVector[Instr[StatefulContext]](MethodSelector(selector0))),
      method.copy(isPublic = false),
      method.copy(instrs = AVector[Instr[StatefulContext]](MethodSelector(selector1)))
    )
    val contract               = StatefulContract(0, methods)
    val (contractObj, context) = prepareContract(contract, AVector.empty, AVector.empty)
    val stackValues =
      AVector[Val](Val.U256(0), Val.U256(0), Val.ByteVec(contractObj.contractId.bytes))
    val frame = StatefulFrame(
      0,
      contractObj,
      Stack.popOnly(stackValues),
      methods.head,
      VarVector.emptyVal,
      _ => okay,
      context,
      None,
      None
    )
    val initialGas = context.gasRemaining
    frame.callExternalBySelector(selector1).isRight is true
    val usedGas = GasSchedule.callGas
      .addUnsafe(GasSchedule.contractLoadGas(contractObj.estimateContractLoadByteSize()))
      .addUnsafe(GasSchedule.selectorCallSearchGas(methods.length))
    initialGas.subUnsafe(context.gasRemaining) is usedGas
    val gasRemaining = context.gasRemaining

    frame.opStack.push(stackValues)
    frame.callExternalBySelector(selector0).isRight is true
    gasRemaining.subUnsafe(context.gasRemaining) is GasSchedule.callGas
  }
}

trait FrameFixture extends ContextGenerators {
  def baseMethod[Ctx <: StatelessContext](
      localsLength: Int,
      usePreapprovedAssets: Boolean = false,
      useAssetsInContract: Boolean = false,
      usePayToContractOnly: Boolean = false
  ) = Method[Ctx](
    isPublic = true,
    usePreapprovedAssets = usePreapprovedAssets,
    useContractAssets = useAssetsInContract,
    usePayToContractOnly = usePayToContractOnly,
    argsLength = localsLength - 1,
    localsLength,
    returnLength = 0,
    instrs = AVector.empty
  )

  def genStatelessFrame(): Frame[StatelessContext] = {
    val method         = baseMethod[StatelessContext](2)
    val script         = StatelessScript.unsafe(AVector(method))
    val (obj, context) = prepareStatelessScript(script)
    Frame
      .stateless(
        context,
        obj,
        method,
        Stack.unsafe(AVector[Val](Val.True, Val.True), 3),
        _ => okay
      )
      .rightValue
  }

  def genStatefulFrame(
      balanceState: Option[MutBalanceState] = None,
      usePreapprovedAssets: Boolean = false,
      useAssetsInContract: Boolean = false,
      usePayToContractOnly: Boolean = false
  )(implicit networkConfig: NetworkConfig): StatefulFrame = {
    val method = baseMethod[StatefulContext](
      2,
      usePreapprovedAssets = usePreapprovedAssets,
      useAssetsInContract = useAssetsInContract,
      usePayToContractOnly = usePayToContractOnly
    )
    val script         = StatefulScript.unsafe(AVector(method))
    val (obj, context) = prepareStatefulScript(script)
    Frame
      .stateful(
        context,
        None,
        balanceState,
        obj,
        method,
        AVector(Val.True),
        Stack.ofCapacity(10),
        _ => okay
      )
      .rightValue
      .asInstanceOf[StatefulFrame]
  }
}

trait PopOpStackFixture extends FrameFixture with ScalaCheckDrivenPropertyChecks {
  val frame = genStatefulFrame()

  def test[A <: Val: ClassTag](popOp: Frame[_] => ExeResult[A]) = {
    forAll(vmValGen) {
      case a: A =>
        frame.opStack.push(a)
        popOp(frame) isE a
      case other =>
        frame.opStack.push(other)
        popOp(frame).leftValue isE a[InvalidType]
    }
  }
}
