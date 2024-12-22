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

import org.alephium.protocol.ALPH
import org.alephium.protocol.config._
import org.alephium.protocol.model._
import org.alephium.protocol.vm.nodeindexes.TxOutputLocator
import org.alephium.util.{AlephiumSpec, AVector, NumericHelpers, TimeStamp}

class ContractPoolSpec extends AlephiumSpec with NumericHelpers {
  trait Fixture extends VMFactory with NetworkConfigFixture.Default {
    val initialGas = GasBox.unsafe(1000000)

    lazy val pool = new ContractPool {
      def getHardFork(): HardFork = networkConfig.getHardFork(TimeStamp.now())

      val worldState: WorldState.Staging = cachedWorldState.staging()
      var gasRemaining: GasBox           = initialGas
    }

    def genContract(
        n: Long = 0,
        fieldLength: Int = 0
    ): (ContractId, StatefulContract, ContractOutputRef, ContractOutput) = {
      val contractId = ContractId.generate
      val output     = ContractOutput(ALPH.alph(n), LockupScript.p2c(contractId), AVector.empty)
      val outputRef  = contractId.inaccurateFirstOutputRef()
      val method = Method[StatefulContext](
        isPublic = true,
        usePreapprovedAssets = false,
        useContractAssets = false,
        usePayToContractOnly = false,
        argsLength = 0,
        localsLength = 0,
        returnLength = 0,
        instrs = AVector(U256Const(Val.U256(n)))
      )
      val contract = StatefulContract(fieldLength, methods = AVector(method))
      (contractId, contract, outputRef, output)
    }

    def fields(length: Int): AVector[Val] = AVector.fill(length)(Val.True)

    def createContract(
        contractId: ContractId,
        contract: StatefulContract,
        outputRef: ContractOutputRef,
        output: ContractOutput,
        immFieldLength: Int,
        mutFieldLength: Int
    ): Assertion = {
      pool.worldState
        .createContractLemanUnsafe(
          contractId,
          contract.toHalfDecoded(),
          fields(immFieldLength),
          fields(mutFieldLength),
          outputRef,
          output,
          TransactionId.generate,
          Some(TxOutputLocator(BlockHash.generate, 0, 0))
        ) isE ()
      pool.worldState.getContractObj(contractId) isE
        contract
          .toHalfDecoded()
          .toObjectUnsafeTestOnly(contractId, fields(immFieldLength), fields(mutFieldLength))
    }

    def createContract(
        n: Long = 0,
        immFieldLength: Int = 0,
        mutFieldLength: Int = 0
    ): (ContractId, StatefulContract) = {
      val (contractId, contract, outputRef, output) =
        genContract(n, immFieldLength + mutFieldLength)
      createContract(contractId, contract, outputRef, output, immFieldLength, mutFieldLength)
      contractId -> contract
    }

    def toObject(contract: StatefulContract, contractId: ContractId) = {
      contract
        .toHalfDecoded()
        .toObjectUnsafeTestOnly(contractId, AVector.empty, fields(contract.fieldLength))
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

  trait ContractNumFixture extends Fixture {
    def prepare() = {
      val contracts = (0 until contractPoolMaxSize + 1).map { k =>
        createContract(k.toLong)
      }
      contracts.init.zipWithIndex.foreach { case ((contractId, contract), index) =>
        pool.loadContractObj(contractId) isE toObject(contract, contractId)
        pool.contractPool.size is index + 1
      }
      contracts
    }
  }

  it should "load limited number of contracts before Rhone" in new ContractNumFixture
    with NetworkConfigFixture.LemanT {
    pool.getHardFork() is HardFork.Leman

    val contracts = prepare()
    pool.loadContractObj(contracts.last._1) is failed(ContractPoolOverflow)
  }

  it should "load unlimited number of contracts from Rhone" in new ContractNumFixture {
    pool.getHardFork() is HardFork.Rhone
    val contracts = prepare()
    pool.loadContractObj(contracts.last._1).isRight is true
  }

  trait FieldNumFixture extends Fixture {
    def prepare() = {
      val (contractId0, contract0) = createContract(0, immFieldLength = contractFieldMaxSize / 2)
      val (contractId1, contract1) = createContract(1, mutFieldLength = contractFieldMaxSize / 2)
      val (contractId2, _)         = createContract(2, 1)
      pool.loadContractObj(contractId0) isE
        contract0
          .toHalfDecoded()
          .toObjectUnsafeTestOnly(contractId0, fields(contract0.fieldLength), AVector.empty)
      pool.loadContractObj(contractId1) isE
        contract1
          .toHalfDecoded()
          .toObjectUnsafeTestOnly(contractId1, AVector.empty, fields(contract1.fieldLength))
      contractId2
    }
  }

  it should "load contracts with limited number of fields before Rhone" in new FieldNumFixture
    with NetworkConfigFixture.LemanT {
    pool.getHardFork() is HardFork.Leman

    val contractId2 = prepare()
    pool.loadContractObj(contractId2) is failed(ContractFieldOverflow)
  }

  it should "load contracts with limited number of fields from Rhone" in new FieldNumFixture {
    pool.getHardFork() is HardFork.Rhone

    val contractId2 = prepare()
    pool.loadContractObj(contractId2).isRight is true
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
    val (contractId, _) = createContract(mutFieldLength = 1)
    val obj             = pool.loadContractObj(contractId).rightValue
    val gasRemaining    = pool.gasRemaining
    pool.updateContractStates().rightValue // no updates
    pool.gasRemaining is gasRemaining
    pool.worldState.getContractState(contractId).rightValue.mutFields is fields(1)
    pool.gasRemaining is gasRemaining
    obj.setMutField(0, Val.False)
    pool.updateContractStates().rightValue
    pool.worldState.getContractState(contractId).rightValue.mutFields is AVector[Val](Val.False)
    pool.gasRemaining is gasRemaining
      .use(GasSchedule.contractStateUpdateBaseGas.addUnsafe(GasBox.unsafe(1)))
      .rightValue
  }

  it should "market assets properly" in new Fixture {
    val contractId0 = ContractId.generate
    val contractId1 = ContractId.generate
    pool.markAssetInUsing(contractId0, MutBalancesPerLockup.empty) isE ()
    pool.markAssetInUsing(contractId0, MutBalancesPerLockup.empty) is failed(
      ContractAssetAlreadyInUsing
    )

    pool.markAssetFlushed(contractId0) isE ()
    pool.markAssetFlushed(contractId0) is failed(ContractAssetAlreadyFlushed)
    pool.markAssetFlushed(contractId1) is failed(
      ContractAssetUnloaded(Address.contract(contractId1))
    )

    pool.checkAllAssetsFlushed() isE ()
    pool.markAssetInUsing(contractId1, MutBalancesPerLockup.empty) isE ()
    pool.checkAllAssetsFlushed() is failed(EmptyContractAsset(Address.contract(contractId1)))
    pool.markAssetFlushed(contractId1) isE ()
    pool.checkAllAssetsFlushed() isE ()
  }

  trait UseContractAssetsFixture
      extends Fixture
      with TxGenerators
      with GroupConfigFixture.Default
      with NetworkConfigFixture.Default {
    val outputRef  = contractOutputRefGen(GroupIndex.unsafe(0)).sample.get
    val contractId = ContractId.random
    val output = contractOutputGen(scriptGen = Gen.const(LockupScript.P2C(contractId))).sample.get
    pool.worldState.createContractLegacyUnsafe(
      contractId,
      StatefulContract.forSMT,
      AVector.empty,
      outputRef,
      output,
      TransactionId.generate,
      Some(TxOutputLocator(BlockHash.generate, 0, 0))
    ) isE ()

    pool.gasRemaining is initialGas
    pool.worldState.getOutputOpt(outputRef).rightValue.nonEmpty is true
    pool.assetStatus.contains(contractId) is false

    val balances = pool.useContractAssets(contractId, 0).rightValue
    initialGas.use(GasSchedule.txInputBaseGas) isE pool.gasRemaining
    pool.worldState.getOutputOpt(outputRef) isE a[Some[_]]
    pool.assetStatus(contractId) is a[ContractPool.ContractAssetInUsing]
  }

  it should "use contract assets wth method-level reentrancy protection since Rhone" in new UseContractAssetsFixture
    with NetworkConfigFixture.SinceRhoneT {
    pool.assetUsedSinceRhone.toSet is Set(contractId -> 0)
    pool.useContractAssets(contractId, 0).leftValue isE FunctionReentrancy(contractId, 0)
    pool.useContractAssets(contractId, 1).rightValue is balances
    pool.assetUsedSinceRhone.toSet is Set(contractId -> 0, contractId -> 1)
  }

  it should "use contract assets wth contract-level reentrancy protection before Rhone" in new UseContractAssetsFixture
    with NetworkConfigFixture.PreRhoneT {
    pool.assetUsedSinceRhone.isEmpty is true
    pool.useContractAssets(contractId, 0).leftValue isE ContractAssetAlreadyInUsing
    pool.useContractAssets(contractId, 1).leftValue isE ContractAssetAlreadyInUsing
    pool.assetUsedSinceRhone.isEmpty is true
  }
}
