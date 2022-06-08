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
import org.scalatest.Assertion

import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.config._
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, NumericHelpers}

class ContractPoolSpec extends AlephiumSpec with NumericHelpers {
  trait Fixture extends VMFactory {
    val initialGas = GasBox.unsafe(1000000)

    val pool = new ContractPool {
      def getHardFork(): HardFork = HardFork.Leman

      val worldState: WorldState.Staging = cachedWorldState.staging()
      var gasRemaining: GasBox           = initialGas
    }

    def genContract(
        n: Long = 0,
        fieldLength: Int = 0
    ): (StatefulContract, ContractOutputRef, ContractOutput) = {
      val contractId = Hash.generate
      val output     = ContractOutput(ALPH.alph(n), LockupScript.p2c(contractId), AVector.empty)
      val outputRef  = ContractOutputRef.unsafe(output.hint, contractId)
      val method = Method[StatefulContext](
        isPublic = true,
        usePreapprovedAssets = false,
        useContractAssets = false,
        argsLength = 0,
        localsLength = 0,
        returnLength = 0,
        instrs = AVector(U256Const(Val.U256(n)))
      )
      val contract = StatefulContract(fieldLength, methods = AVector(method))
      (contract, outputRef, output)
    }

    def fields(length: Int): AVector[Val] = AVector.fill(length)(Val.True)

    def createContract(
        contract: StatefulContract,
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): Assertion = {
      pool.worldState
        .createContractUnsafe(
          contract.toHalfDecoded(),
          fields(contract.fieldLength),
          outputRef,
          output
        ) isE ()
      val contractId = outputRef.key
      pool.worldState.getContractObj(contractId) isE
        contract.toHalfDecoded().toObjectUnsafe(contractId, fields(contract.fieldLength))
    }

    def createContract(n: Long = 0, fieldLength: Int = 0): (Hash, StatefulContract) = {
      val (contract, outputRef, output) = genContract(n, fieldLength)
      createContract(contract, outputRef, output)
      outputRef.key -> contract
    }

    def toObject(contract: StatefulContract, contractId: ContractId) = {
      contract
        .toHalfDecoded()
        .toObjectUnsafe(contractId, fields(contract.fieldLength))
    }
  }

  it should "load contract from cache since the second fetch" in new Fixture {
    pool.contractPool.size is 0

    val (contractId, contract) = createContract()
    pool.loadContractObj(contractId)
    pool.worldState.rollback() // remove the cached contract
    pool.worldState.getContractObj(contractId).isLeft is true

    pool.contractPool.size is 1
    pool.loadContractObj(contractId) isE toObject(contract, contractId)
    pool.contractPool.size is 1
    pool.loadContractObj(contractId) isE toObject(contract, contractId)
    pool.contractPool.size is 1
  }

  it should "load limited number of contracts" in new Fixture {
    val contracts = (0 until contractPoolMaxSize + 1).map { k =>
      createContract(k.toLong)
    }
    contracts.init.zipWithIndex.foreach { case ((contractId, contract), index) =>
      pool.loadContractObj(contractId) isE toObject(contract, contractId)
      pool.contractPool.size is index + 1
    }
    pool.loadContractObj(contracts.last._1) is failed(ContractPoolOverflow)
  }

  it should "load contracts with limited number of fields" in new Fixture {
    val (contractId0, contract0) = createContract(0, contractFieldMaxSize / 2)
    val (contractId1, contract1) = createContract(1, contractFieldMaxSize / 2)
    val (contractId2, _)         = createContract(2, 1)
    pool.loadContractObj(contractId0) isE toObject(contract0, contractId0)
    pool.loadContractObj(contractId1) isE toObject(contract1, contractId1)
    pool.loadContractObj(contractId2) is failed(ContractFieldOverflow)
  }

  it should "charge IO fee once when loading a contract multiple times" in new Fixture {
    val (contractId, contract) = createContract()
    pool.gasRemaining is initialGas
    pool.loadContractObj(contractId).rightValue
    pool.gasRemaining is initialGas
      .use(GasSchedule.contractLoadGas(contract.toHalfDecoded().methodsBytes.length))
      .rightValue
    val remainingGas = pool.gasRemaining
    pool.loadContractObj(contractId).rightValue
    pool.gasRemaining is remainingGas
  }

  it should "update contract only when the state of the contract is altered" in new Fixture {
    val (contractId, _) = createContract(fieldLength = 1)
    val obj             = pool.loadContractObj(contractId).rightValue
    val gasRemaining    = pool.gasRemaining
    pool.updateContractStates().rightValue // no updates
    pool.gasRemaining is gasRemaining
    pool.worldState.getContractState(contractId).rightValue.fields is fields(1)
    pool.gasRemaining is gasRemaining
    obj.setField(0, Val.False)
    pool.updateContractStates().rightValue
    pool.worldState.getContractState(contractId).rightValue.fields is AVector[Val](Val.False)
    pool.gasRemaining is gasRemaining
      .use(GasSchedule.contractStateUpdateBaseGas.addUnsafe(GasBox.unsafe(1)))
      .rightValue
  }

  it should "market assets properly" in new Fixture {
    val contractId0 = Hash.generate
    val contractId1 = Hash.generate
    pool.markAssetInUsing(contractId0) isE ()
    pool.markAssetInUsing(contractId0) is failed(ContractAssetAlreadyInUsing)

    pool.markAssetFlushed(contractId0) isE ()
    pool.markAssetFlushed(contractId0) is failed(ContractAssetAlreadyFlushed)
    pool.markAssetFlushed(contractId1) is failed(ContractAssetUnloaded)

    pool.checkAllAssetsFlushed() isE ()
    pool.markAssetInUsing(contractId1) isE ()
    pool.checkAllAssetsFlushed() is failed(EmptyContractAsset)
    pool.markAssetFlushed(contractId1) isE ()
    pool.checkAllAssetsFlushed() isE ()
  }

  it should "use contract assets" in new Fixture
    with TxGenerators
    with GroupConfigFixture.Default
    with NetworkConfigFixture.Default {
    val outputRef  = contractOutputRefGen(GroupIndex.unsafe(0)).sample.get
    val contractId = outputRef.key
    val output = contractOutputGen(scriptGen = Gen.const(LockupScript.P2C(contractId))).sample.get
    pool.worldState.createContractUnsafe(
      StatefulContract.forSMT,
      AVector.empty,
      outputRef,
      output
    ) isE ()

    pool.gasRemaining is initialGas
    pool.worldState.getOutputOpt(outputRef).rightValue.nonEmpty is true
    pool.useContractAssets(contractId).isRight is true
    initialGas.use(GasSchedule.txInputBaseGas) isE pool.gasRemaining
    pool.worldState.getOutputOpt(outputRef) isE None
  }
}
