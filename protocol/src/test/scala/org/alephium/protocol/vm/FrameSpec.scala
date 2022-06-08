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
import org.alephium.protocol.vm.ContractPool.{ContractAssetInUsing}
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
      val (code, state, contractOutputRef, contractOutput) = generateContract().sample.get
      cachedWorldState.createContractUnsafe(
        code,
        state,
        contractOutputRef,
        contractOutput
      )
      contractOutputRef.key
    }

    val from = lockupScriptGen.sample.get
    def balanceState =
      MutBalanceState(
        MutBalances.empty,
        MutBalances(ArrayBuffer(from -> MutBalancesPerLockup.alph(ALPH.alph(1000))))
      )
    def preLemanFrame = {
      genStatefulFrame(Some(balanceState))(NetworkConfigFixture.PreLeman)
    }
    def lemanFrame = {
      val balanceState =
        MutBalanceState(
          MutBalances.empty,
          MutBalances(ArrayBuffer(from -> MutBalancesPerLockup.alph(ALPH.alph(1000))))
        )
      genStatefulFrame(Some(balanceState))(NetworkConfigFixture.Leman)
    }

    val contract0 = StatefulContract(0, AVector(Method(true, true, true, 0, 0, 0, AVector.empty)))
    val contract1 = StatefulContract(0, AVector(Method(true, false, false, 0, 0, 0, AVector.empty)))
    val contract2 = StatefulContract(0, AVector(Method(true, true, false, 0, 0, 0, AVector.empty)))
    val contract3 = StatefulContract(0, AVector(Method(true, false, true, 0, 0, 0, AVector.empty)))

    def test(_frame: => StatefulFrame, contract: StatefulContract, emptyOutput: Boolean) = {
      val contractId = prepareContract()
      val method     = contract.methods.head
      val frame      = _frame
      frame.balanceStateOpt.get.approved.all.isEmpty is false

      val result = frame
        .getNewFrameBalancesState(
          StatefulContractObject.from(contract, AVector.empty, contractId),
          method
        )

      result.rightValue.isEmpty is emptyOutput
      if (!emptyOutput) {
        if (method.usePreapprovedAssets) {
          frame.balanceStateOpt.get.approved.all.isEmpty is true
        }
        if (method.useContractAssets) {
          frame.ctx.assetStatus(contractId) is ContractAssetInUsing
        }
      }
    }
  }

  it should "calculate frame balances" in new FrameBalanceFixture {
    test(preLemanFrame, contract0, emptyOutput = false)
    test(preLemanFrame, contract1, emptyOutput = true)
    test(preLemanFrame, contract2, emptyOutput = false)
    test(preLemanFrame, contract3, emptyOutput = true)
    test(lemanFrame, contract0, emptyOutput = false)
    test(lemanFrame, contract1, emptyOutput = true)
    test(lemanFrame, contract2, emptyOutput = false)
    test(lemanFrame, contract3, emptyOutput = false)
  }
}

trait FrameFixture extends ContextGenerators {
  def baseMethod[Ctx <: StatelessContext](localsLength: Int) = Method[Ctx](
    isPublic = true,
    usePreapprovedAssets = false,
    useContractAssets = false,
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
      balanceState: Option[MutBalanceState] = None
  )(implicit networkConfig: NetworkConfig): StatefulFrame = {
    val method         = baseMethod[StatefulContext](2)
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
