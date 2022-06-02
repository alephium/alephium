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

import org.scalacheck.Gen

import org.alephium.protocol.Hash
import org.alephium.protocol.config.{GroupConfigFixture, NetworkConfigFixture}
import org.alephium.protocol.model.{ContractId, GroupIndex, HardFork, TxGenerators, TxOutputRef}
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

class ContextSpec
    extends AlephiumSpec
    with ContextGenerators
    with TxGenerators
    with GroupConfigFixture.Default {
  trait Fixture extends NetworkConfigFixture.Default {
    lazy val initialGas = 1000000
    lazy val context    = genStatefulContext(None, gasLimit = initialGas)

    def createContract(): ContractId = {
      val output = contractOutputGen(scriptGen = Gen.const(LockupScript.P2C(Hash.zero))).sample.get
      val balances = MutBalancesPerLockup.from(output)
      context
        .createContract(
          StatefulContract.forSMT,
          balances,
          AVector.empty,
          None
        )
        .rightValue
      val contractId = TxOutputRef.key(context.txId, context.txEnv.fixedOutputs.length)
      context.worldState.getContractState(contractId).isRight is true
      MutBalancesPerLockup.from(
        context.worldState.getContractAsset(contractId).rightValue
      ) is balances
      context.generatedOutputs.size is 1

      context.checkIfBlocked(contractId).leftValue isE ContractLoadDisallowed(contractId)
      context.contractBlockList.remove(contractId)
      contractId
    }
  }

  it should "generate asset output" in new Fixture {
    val assetOutput = assetOutputGen(GroupIndex.unsafe(0))().sample.get
    context.generateOutput(assetOutput) isE ()
    initialGas.use(GasSchedule.txOutputBaseGas) isE context.gasRemaining
    context.generatedOutputs.size is 1
  }

  it should "generate contract output when the contract is not loaded" in new Fixture {
    val contractId = createContract()
    val newOutput =
      contractOutputGen(scriptGen = Gen.const(contractId).map(LockupScript.p2c)).sample.get
    context.generateOutput(newOutput).leftValue isE ContractAssetUnloaded
    initialGas.use(GasSchedule.txOutputBaseGas) isE context.gasRemaining
    context.worldState.getContractAsset(contractId) isE newOutput
    context.generatedOutputs.size is 1
  }

  it should "generate contract output when the contract is loaded" in new Fixture {
    val contractId = createContract()
    val newOutput =
      contractOutputGen(scriptGen = Gen.const(contractId).map(LockupScript.p2c)).sample.get
    context.loadContractObj(contractId).isRight is true
    context.useContractAssets(contractId).isRight is true
    context.generateOutput(newOutput) isE ()
    context.worldState.getContractAsset(contractId) isE newOutput
    (initialGas.value -
      GasSchedule.contractLoadGas(StatefulContract.forSMT.methodsBytes.length).value -
      GasSchedule.txInputBaseGas.value -
      GasSchedule.txOutputBaseGas.value) is context.gasRemaining.value
    context.generatedOutputs.size is 2
  }

  it should "migrate contract without state change" in new Fixture {
    val contractId = createContract()
    val obj        = context.loadContractObj(contractId).rightValue
    val newCode: StatefulContract =
      StatefulContract(0, AVector(Method.forSMT, Method.forSMT))
    context.migrateContract(contractId, obj, newCode, None) isE ()
    context.contractPool.contains(contractId) is false
    context.contractBlockList.contains(contractId) is true

    context.contractBlockList.remove(contractId)
    val newObj = context.loadContractObj(contractId).rightValue
    newObj.code is newCode.toHalfDecoded()
    newObj.codeHash is newCode.hash
    newObj.initialStateHash is obj.initialStateHash
    newObj.contractId is contractId
  }

  it should "migrate contract with state change" in new Fixture {
    val contractId = createContract()
    val obj        = context.loadContractObj(contractId).rightValue
    val newCode: StatefulContract =
      StatefulContract(1, AVector(Method.forSMT, Method.forSMT))
    context.migrateContract(contractId, obj, newCode, None).leftValue isE InvalidFieldLength
    context.migrateContract(contractId, obj, newCode, Some(AVector(Val.True))) isE ()
    context.contractPool.contains(contractId) is false
    context.contractBlockList.contains(contractId) is true

    context.contractBlockList.remove(contractId)
    val newObj = context.loadContractObj(contractId).rightValue
    newObj.code is newCode.toHalfDecoded()
    newObj.codeHash is newCode.hash
    newObj.initialStateHash is obj.initialStateHash
    newObj.contractId is contractId
  }

  it should "charge gas based on mainnet hardfork" in new Fixture {
    override def lemanHardForkTimestamp: TimeStamp = TimeStamp.now().plusHoursUnsafe(1)
    context.getHardFork() is HardFork.Mainnet

    context.chargeGasWithSizeLeman(ByteVecEq, 7)
    val expected0 = initialGas.use(GasBox.unsafe(1)).rightValue
    context.gasRemaining is expected0

    context.chargeGasWithSizeLeman(ByteVecConcat, 7)
    val expected1 = expected0.use(GasBox.unsafe(7)).rightValue
    context.gasRemaining is expected1
  }

  it should "charge gas based on leman hardfork" in new Fixture {
    context.getHardFork() is HardFork.Leman

    context.chargeGasWithSizeLeman(ByteVecEq, 7)
    val expected0 = initialGas.use(GasBox.unsafe(4)).rightValue
    context.gasRemaining is expected0

    context.chargeGasWithSizeLeman(ByteVecConcat, 7)
    val expected1 = expected0.use(GasBox.unsafe(10)).rightValue
    context.gasRemaining is expected1
  }
}
