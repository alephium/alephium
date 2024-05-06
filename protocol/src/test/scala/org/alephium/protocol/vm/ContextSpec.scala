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

import akka.util.ByteString
import org.scalacheck.Gen

import org.alephium.protocol.{ALPH, PublicKey}
import org.alephium.protocol.config.{GroupConfigFixture, NetworkConfigFixture}
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp, U256}

class ContextSpec
    extends AlephiumSpec
    with ContextGenerators
    with TxGenerators
    with GroupConfigFixture.Default {
  trait Fixture extends NetworkConfigFixture.Default {
    lazy val initialGas = 1000000
    lazy val context    = genStatefulContext(None, gasLimit = initialGas)

    def createContract(unblock: Boolean = true): ContractId = {
      val output =
        contractOutputGen(scriptGen = Gen.const(LockupScript.P2C(ContractId.zero))).sample.get
      val balances = MutBalancesPerLockup.from(output)
      val contractId = ContractId.from(
        context.txId,
        context.txEnv.fixedOutputs.length,
        context.blockEnv.chainIndex.from
      )
      context
        .createContract(
          contractId,
          StatefulContract.forSMT,
          AVector.empty,
          balances,
          AVector.empty,
          None
        )
        .rightValue
      context.worldState.getContractState(contractId).isRight is true
      MutBalancesPerLockup.from(
        context.worldState.getContractAsset(contractId).rightValue
      ) is balances
      context.generatedOutputs.size is 1

      context.checkIfBlocked(contractId).leftValue isE ContractLoadDisallowed(contractId)
      if (unblock) {
        context.contractBlockList.remove(contractId)
      }
      contractId
    }
  }

  it should "test contract exists" in new Fixture {
    val contractId0 = ContractId.random
    context.contractExists(contractId0) isE false
    val contractId1 = createContract()
    context.contractExists(contractId1) isE true
  }

  it should "generate asset output" in new Fixture {
    val assetOutput = assetOutputGen(GroupIndex.unsafe(0))(_tokensGen =
      tokensGen(1, Gen.choose(1, maxTokenPerAssetUtxo))
    ).sample.get
    context.generateOutput(assetOutput) isE ()
    initialGas.use(GasSchedule.txOutputBaseGas) isE context.gasRemaining
    context.generatedOutputs.size is 1
  }

  it should "not generate contract output when the contract is not loaded" in new Fixture {
    val contractId = createContract()
    val oldOutput  = context.worldState.getContractAsset(contractId).rightValue
    val newOutput =
      contractOutputGen(scriptGen = Gen.const(contractId).map(LockupScript.p2c)).sample.get
    context.generateOutput(newOutput).leftValue isE a[ContractAssetUnloaded]
    context.gasRemaining is initialGas
    context.worldState.getContractAsset(contractId) isE oldOutput
    context.generatedOutputs.size is 1
  }

  it should "generate contract output when the contract is loaded before Rhone upgrade" in new Fixture
    with NetworkConfigFixture.LemanT {
    context.getHardFork() is HardFork.Leman
    val contractId   = createContract()
    val oldOutputRef = context.worldState.getContractState(contractId).rightValue.contractOutputRef
    val newOutput =
      contractOutputGen(scriptGen = Gen.const(contractId).map(LockupScript.p2c)).sample.get
    context.loadContractObj(contractId).isRight is true
    context.useContractAssets(contractId, 0).isRight is true
    context.generateOutput(newOutput) isE ()

    val newOutputRef = context.worldState.getContractState(contractId).rightValue.contractOutputRef
    context.worldState.getContractAsset(contractId) isE newOutput
    newOutputRef isnot oldOutputRef
    (initialGas.value -
      GasSchedule.contractLoadGas(StatefulContract.forSMT.methodsBytes.length).value -
      GasSchedule.txInputBaseGas.value -
      GasSchedule.txOutputBaseGas.value) is context.gasRemaining.value
    context.generatedOutputs.size is 2
  }

  it should "not generate contract output when the contract is loaded from Rhone upgrade" in new Fixture {
    context.getHardFork() is HardFork.Ghost
    val contractId = createContract()
    context.loadContractObj(contractId).isRight is true
    context.useContractAssets(contractId, 0).leftValue.rightValue is ContractAssetAlreadyFlushed
  }

  it should "not cache new contract before Rhone upgrade" in new Fixture
    with NetworkConfigFixture.LemanT {
    context.getHardFork() is HardFork.Leman
    val contractId = createContract(unblock = false)
    context.contractBlockList.contains(contractId) is true
    context.contractPool.contains(contractId) is false
  }

  it should "cache new contract from Rhone upgrade" in new Fixture {
    context.getHardFork() is HardFork.Ghost
    val contractId = createContract(unblock = false)
    context.contractBlockList.contains(contractId) is true
    context.contractPool.contains(contractId) is true
    context.loadContractObj(contractId).isRight is true
  }

  it should "migrate contract without state change" in new Fixture {
    val contractId = createContract()
    val obj        = context.loadContractObj(contractId).rightValue
    val newCode: StatefulContract =
      StatefulContract(0, AVector(Method.forSMT, Method.forSMT))
    context.migrateContract(contractId, obj, newCode, None, None) isE ()
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
    context.migrateContract(contractId, obj, newCode, None, None).leftValue isE InvalidFieldLength
    context.migrateContract(
      contractId,
      obj,
      newCode,
      Some(AVector.empty),
      Some(AVector(Val.True))
    ) isE ()
    context.contractPool.contains(contractId) is false
    context.contractBlockList.contains(contractId) is true

    context.contractBlockList.remove(contractId)
    val newObj = context.loadContractObj(contractId).rightValue
    newObj.code is newCode.toHalfDecoded()
    newObj.codeHash is newCode.hash
    newObj.initialStateHash is obj.initialStateHash
    newObj.contractId is contractId
  }

  it should "charge gas based on mainnet hardfork" in new Fixture
    with NetworkConfigFixture.GenesisT {
    context.getHardFork() is HardFork.Mainnet

    context.chargeGasWithSizeLeman(ByteVecEq, 7)
    val expected0 = initialGas.use(GasBox.unsafe(1)).rightValue
    context.gasRemaining is expected0

    context.chargeGasWithSizeLeman(ByteVecConcat, 7)
    val expected1 = expected0.use(GasBox.unsafe(7)).rightValue
    context.gasRemaining is expected1
  }

  it should "charge gas based on leman hardfork" in new Fixture
    with NetworkConfigFixture.SinceLemanT {
    Seq(HardFork.Leman, HardFork.Ghost).contains(context.getHardFork()) is true

    context.chargeGasWithSizeLeman(ByteVecEq, 7)
    val expected0 = initialGas.use(GasBox.unsafe(4)).rightValue
    context.gasRemaining is expected0

    context.chargeGasWithSizeLeman(ByteVecConcat, 7)
    val expected1 = expected0.use(GasBox.unsafe(10)).rightValue
    context.gasRemaining is expected1
  }

  trait ContractOutputFixture extends NetworkConfigFixture.Default {
    val contractId = ContractId.random
    val tokenId0   = TokenId.random
    val tokenId1   = TokenId.random
    val outputRef  = contractOutputRefGen(GroupIndex.unsafe(0)).sample.get
    val output =
      ContractOutput(100, LockupScript.p2c(contractId), AVector(tokenId0 -> 200, tokenId1 -> 300))
    val modifiedOutputs = Seq(
      ContractOutput(101, LockupScript.p2c(contractId), AVector(tokenId0 -> 200, tokenId1 -> 300)),
      ContractOutput(100, LockupScript.p2c(contractId), AVector(tokenId0 -> 201, tokenId1 -> 300)),
      ContractOutput(100, LockupScript.p2c(contractId), AVector(tokenId0 -> 200, tokenId1 -> 301)),
      ContractOutput(100, LockupScript.p2c(contractId), AVector(tokenId0 -> 200)),
      ContractOutput(100, LockupScript.p2c(contractId), AVector(tokenId1 -> 300)),
      ContractOutput(100, LockupScript.p2c(contractId), AVector(tokenId1 -> 200, tokenId0 -> 300))
    )
    lazy val context = {
      lazy val initialGas = 1000000
      lazy val context    = genStatefulContext(None, gasLimit = initialGas)
      context.contractInputs.clear()
      context.contractInputs += outputRef -> output
      context.markAssetInUsing(contractId, MutBalancesPerLockup.empty)
      context.worldState.createContractLegacyUnsafe(
        contractId,
        StatefulContract.forSMT,
        AVector.empty,
        outputRef,
        output
      )
      context
    }

    def testOutputDifferentFromInput() = {
      modifiedOutputs.foreach { modifiedOutput =>
        modifiedOutput isnot output
        val initialGas = context.gasRemaining
        context.generatedOutputs.clear()
        context.assetStatus.put(
          contractId,
          ContractPool.ContractAssetInUsing(MutBalancesPerLockup.empty)
        )
        context.generateOutput(modifiedOutput) isE ()
        context.contractInputs.toSeq is Seq(outputRef -> output)
        context.generatedOutputs.toSeq is Seq(modifiedOutput)
        context.gasRemaining is initialGas.subUnsafe(GasSchedule.txOutputBaseGas) // no refund
      }
    }
  }

  trait MainnetContractOutputFixture
      extends ContractOutputFixture
      with NetworkConfigFixture.GenesisT {
    context.getHardFork() is HardFork.Mainnet
  }

  trait LemanContractOutputFixture
      extends ContractOutputFixture
      with NetworkConfigFixture.SinceLemanT {
    Seq(HardFork.Leman, HardFork.Ghost).contains(context.getHardFork()) is true
  }

  it should "generate output when the output is the same as input for Mainnet hardfork" in new MainnetContractOutputFixture {
    val initialGas = context.gasRemaining
    context.generateOutput(output) isE ()
    context.contractInputs.toSeq is Seq(outputRef -> output)
    context.generatedOutputs.toSeq is Seq(output)
    context.gasRemaining is initialGas.subUnsafe(GasSchedule.txOutputBaseGas)
  }

  it should "generate output when the output is not the same as input for Mainnet hardfork" in new MainnetContractOutputFixture {
    testOutputDifferentFromInput()
  }

  it should "fail to generate output when contract asset is not loaded for Mainnet hardfork" in new MainnetContractOutputFixture {
    val newContext = genStatefulContext()
    newContext.generateOutput(output).leftValue isE a[ContractAssetUnloaded]
  }

  it should "ignore output when the output is the same as input for Leman hardfork" in new LemanContractOutputFixture {
    val initialGas = context.gasRemaining
    context.generateOutput(output) isE ()
    context.contractInputs.isEmpty is true
    context.generatedOutputs.isEmpty is true
    context.gasRemaining is initialGas
  }

  it should "generate output when the output is not the same as input for Leman hardfork" in new LemanContractOutputFixture {
    testOutputDifferentFromInput()
  }

  it should "fail to generate output when contract asset is not loaded for Leman hardfork" in new LemanContractOutputFixture {
    val newContext = genStatefulContext()
    newContext.generateOutput(output).leftValue isE a[ContractAssetUnloaded]
  }

  trait AssetOutputFixture extends Fixture {
    def prepareOutput(alphAmount: U256, tokenNum: Int): AssetOutput = {
      AssetOutput(
        alphAmount,
        LockupScript.p2pkh(PublicKey.generate),
        TimeStamp.zero,
        AVector.fill(tokenNum)((TokenId.random, U256.One)),
        ByteString.empty
      )
    }
  }

  trait MainnetAssetOutputFixture extends AssetOutputFixture with NetworkConfigFixture.GenesisT {
    context.getHardFork() is HardFork.Mainnet
  }

  trait LemanAssetOutputFixture extends AssetOutputFixture with NetworkConfigFixture.SinceLemanT {
    Seq(HardFork.Leman, HardFork.Ghost).contains(context.getHardFork()) is true
  }

  it should "generate single output when token number <= maxTokenPerUTXO for Mainnet hardfork" in new MainnetAssetOutputFixture {
    (0 to maxTokenPerAssetUtxo).foreach { num =>
      val output = prepareOutput(ALPH.oneAlph, num)
      context.generatedOutputs.clear()
      context.generateOutput(output) isE ()
      context.generatedOutputs.toSeq is Seq(output)
    }
  }

  it should "generate single output when token number > maxTokenPerUTXO for Mainnet hardfork" in new MainnetAssetOutputFixture {
    (maxTokenPerAssetUtxo + 1 to 5 * maxTokenPerAssetUtxo).foreach { num =>
      val output = prepareOutput(ALPH.oneAlph, num)
      context.generatedOutputs.clear()
      context.generateOutput(output) isE ()
      context.generatedOutputs.toSeq is Seq(output)
    }
  }

  it should "generate single output when token number <= maxTokenPerUTXO for Leman hardfork" in new LemanAssetOutputFixture {
    (0 to maxTokenPerAssetUtxo).foreach { num =>
      val output = prepareOutput(ALPH.oneAlph, num)
      context.generatedOutputs.clear()
      context.generateOutput(output) isE ()
      context.generatedOutputs.toSeq is Seq(output)
    }
  }

  trait OutputRemainingContractAssetsFixture extends Fixture {
    val contractId0 = ContractId.random
    val contractId1 = ContractId.random

    def prepare(): Unit = {
      context.assetStatus.isEmpty is true
      context.assetStatus(contractId0) =
        ContractPool.ContractAssetInUsing(MutBalancesPerLockup.empty)
      context.assetStatus(contractId1) = ContractPool.ContractAssetFlushed
    }
  }

  it should "output remaining contract assets since Rhone" in new OutputRemainingContractAssetsFixture
    with NetworkConfigFixture.SinceRhoneT {
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Ghost

    prepare()
    context.outputRemainingContractAssetsForRhone() isE ()
    context.outputBalances.all.length is 1
    context.outputBalances.all.head._1 is LockupScript.p2c(contractId0)
  }

  it should "not output remaining contract assets before Rhone" in new OutputRemainingContractAssetsFixture
    with NetworkConfigFixture.PreRhoneT {
    Seq(HardFork.Mainnet, HardFork.Leman).contains(
      networkConfig.getHardFork(TimeStamp.now())
    ) is true

    prepare()
    context.outputRemainingContractAssetsForRhone() isE ()
    context.outputBalances.all.length is 0
  }
}
