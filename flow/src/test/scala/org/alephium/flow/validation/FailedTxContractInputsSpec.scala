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

package org.alephium.flow.validation

import org.apache.pekko.util.ByteString

import org.alephium.crypto.Byte64
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.{ALPH, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.util.{AVector, TimeStamp}

/** A failed tx script does not spend the contract inputs it declares, while those inputs are still
  * counted by `checkAlphBalance`/`checkTokenBalance`. Without an explicit check, a failed tx could
  * pay out a contract's assets to the attacker without spending them, i.e. mint new assets.
  */
// scalastyle:off method.length
class FailedTxContractInputsSpec extends AlephiumFlowSpec {

  it should "reject a failed tx script that declares contract inputs" in {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val groupIndex = chainIndex.from

    // a live contract holding some ALPH
    val contractAmount = ALPH.alph(100)
    val (contractId, contractOutputRef, _) = createContract(
      """
        |Contract FailedTxProbe() {
        |  pub fn deposit() -> () {
        |    return
        |  }
        |}
        |""".stripMargin,
      initialAttoAlphAmount = contractAmount,
      chainIndex = chainIndex
    )
    val worldState            = blockFlow.getBestCachedWorldState(groupIndex).rightValue
    val (liveRef, liveOutput) = worldState.loadContractAssets(contractId).rightValue
    liveRef is contractOutputRef
    liveOutput.amount is contractAmount

    // one UTXO owned by the attacker
    val attackerPrivateKey = genesisKeys(groupIndex.value)._1
    val attackerPublicKey  = attackerPrivateKey.publicKey
    val attackerLockup     = LockupScript.p2pkh(attackerPublicKey)
    val utxo = blockFlow
      .getUsableUtxos(attackerLockup, defaultUtxoLimit)
      .rightValue
      .maxBy(_.output.amount.v)

    val gasPrice  = nonCoinbaseMinGasPrice
    val gasAmount = GasBox.unsafe(100000)
    val gasFee    = gasPrice * gasAmount
    val changeOutput = AssetOutput(
      utxo.output.amount - gasFee,
      attackerLockup,
      TimeStamp.zero,
      AVector.empty,
      ByteString.empty
    )
    val mintedOutput = AssetOutput(
      contractAmount,
      attackerLockup,
      TimeStamp.zero,
      AVector.empty,
      ByteString.empty
    )

    val unsigned = UnsignedTransaction(
      Some(StatefulScript.alwaysFail),
      AVector(TxInput(utxo.ref, UnlockScript.p2pkh(attackerPublicKey))),
      AVector(changeOutput)
    ).copy(gasAmount = gasAmount)
    val signature = Byte64.from(SignatureSchema.sign(unsigned.id, attackerPrivateKey))

    def buildTx(
        contractInputs: AVector[ContractOutputRef],
        generatedOutputs: AVector[TxOutput],
        scriptExecutionOk: Boolean
    ): Transaction =
      Transaction(
        unsigned,
        scriptExecutionOk,
        contractInputs,
        generatedOutputs,
        AVector(signature),
        AVector.empty
      )

    // the attack tx pays the contract's balance to the attacker, using the contract output as input
    val attackTx =
      buildTx(AVector(contractOutputRef), AVector[TxOutput](mintedOutput), false)
    // the same failed tx without contract inputs is legitimate
    val controlTx = buildTx(AVector.empty, AVector.empty[TxOutput], false)

    val txValidation = TxValidation.build
    txValidation
      .validateTxOnlyForTest(attackTx, blockFlow, None)
      .leftValue isE ContractInputsShouldBeEmptyForFailedTxScripts
    txValidation.validateTxOnlyForTest(controlTx, blockFlow, None) isE ()

    // a block containing the attack tx is rejected as well
    val hardFork = networkConfig.getHardFork(TimeStamp.now())
    val deps     = blockFlow.getBestDeps(chainIndex, hardFork)
    val parentTs = blockFlow.getBlockHeaderUnsafe(deps.parentHash(chainIndex)).timestamp
    val now      = TimeStamp.now()
    val blockTs  = if (now > parentTs) now else parentTs.plusMillisUnsafe(1)
    val attackBlock =
      mineWithoutCoinbase(blockFlow, chainIndex, AVector(attackTx), blockTs)
    BlockValidation
      .build(blockFlow)
      .validate(attackBlock, blockFlow)
      .leftValue isE ExistInvalidTx(attackTx, ContractInputsShouldBeEmptyForFailedTxScripts)
  }
}
// scalastyle:on method.length
