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

import scala.util.Random

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.{ALF, Hash, Signature}
import org.alephium.protocol.model._
import org.alephium.protocol.model.ModelGenerators.AssetInputInfo
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, VMFactory}
import org.alephium.util.{AVector, TimeStamp, U256}

class TxValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  def passCheck[T](result: TxValidationResult[T]): Assertion = {
    result.isRight is true
  }

  def failCheck[T](result: TxValidationResult[T], error: InvalidTxStatus): Assertion = {
    result.left.value isE error
  }

  def passValidation(result: TxValidationResult[Unit]): Assertion = {
    result.isRight is true
  }

  def failValidation(result: TxValidationResult[Unit], error: InvalidTxStatus): Assertion = {
    result.left.value isE error
  }

  class Fixture extends TxValidation.Impl with VMFactory {
    // TODO: prepare blockflow to test checkMempool
    def prepareWorldState(inputInfos: AVector[AssetInputInfo]): Unit = {
      inputInfos.foreach { inputInfo =>
        cachedWorldState.addAsset(inputInfo.txInput.outputRef, inputInfo.referredOutput) isE ()
      }
    }

    def checkBlockTx(
        tx: Transaction,
        preOutputs: AVector[AssetInputInfo],
        headerTs: TimeStamp = TimeStamp.now()
    ): TxValidationResult[Unit] = {
      prepareWorldState(preOutputs)
      for {
        chainIndex <- getChainIndex(tx)
        _          <- checkStateless(chainIndex, tx, checkDoubleSpending = true)
        _          <- checkStateful(chainIndex, tx, headerTs, cachedWorldState, None)
      } yield ()
    }

    def validateMempoolTx(
        tx: Transaction,
        flow: BlockFlow
    ): TxValidationResult[Unit] = {
      for {
        chainIndex <- getChainIndex(tx)
        _          <- validateMempoolTx(chainIndex, tx, flow)
      } yield ()
    }
  }

  it should "pass valid transactions" in new Fixture {
    forAll(
      transactionGenWithPreOutputs(1, 1, chainIndexGen = chainIndexGenForBroker(brokerConfig))
    ) { case (tx, preOutputs) =>
      passCheck(checkBlockTx(tx, preOutputs))
    }
  }

  behavior of "Stateless Validation"

  trait StatelessFixture extends Fixture {
    val blockFlow = genesisBlockFlow()

    def modifyAlfAmount(tx: Transaction, delta: U256): Transaction = {
      val (index, output) = tx.unsigned.fixedOutputs.sampleWithIndex()
      val outputNew       = output.copy(amount = output.amount + delta)
      tx.copy(
        unsigned =
          tx.unsigned.copy(fixedOutputs = tx.unsigned.fixedOutputs.replace(index, outputNew))
      )
    }

    def zeroAlfAmount(tx: Transaction): Transaction = {
      val (index, output) = tx.unsigned.fixedOutputs.sampleWithIndex()
      val outputNew       = output.copy(amount = 0)
      tx.copy(
        unsigned =
          tx.unsigned.copy(fixedOutputs = tx.unsigned.fixedOutputs.replace(index, outputNew))
      )
    }

    def zeroTokenAmount(tx: Transaction): Transaction = {
      val (index, output) = tx.unsigned.fixedOutputs.sampleWithIndex()
      val outputNew       = output.copy(tokens = AVector(Hash.generate -> U256.Zero))
      tx.copy(
        unsigned =
          tx.unsigned.copy(fixedOutputs = tx.unsigned.fixedOutputs.replace(index, outputNew))
      )
    }
  }

  it should "check empty outputs" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs(1, 1)) { case (tx, preOutputs) =>
      val unsignedNew = tx.unsigned.copy(fixedOutputs = AVector.empty)
      val txNew       = tx.copy(unsigned = unsignedNew)
      failCheck(checkOutputNum(txNew), NoOutputs)
      failValidation(validateMempoolTx(txNew, blockFlow), NoOutputs)
      failCheck(checkBlockTx(txNew, preOutputs), NoOutputs)
    }
  }

  it should "check gas bounds" in new StatelessFixture {
    val tx = transactionGen(1, 1).sample.get
    passCheck(checkGasBound(tx))

    val txNew0 = tx.copy(unsigned = tx.unsigned.copy(startGas = GasBox.unsafeTest(-1)))
    failCheck(checkGasBound(txNew0), InvalidStartGas)
    failValidation(validateMempoolTx(txNew0, blockFlow), InvalidStartGas)
    val txNew1 = tx.copy(unsigned = tx.unsigned.copy(startGas = GasBox.unsafeTest(0)))
    failCheck(checkGasBound(txNew1), InvalidStartGas)
    failValidation(validateMempoolTx(txNew1, blockFlow), InvalidStartGas)
    val txNew2 = tx.copy(unsigned = tx.unsigned.copy(startGas = minimalGas.use(1).rightValue))
    failCheck(checkGasBound(txNew2), InvalidStartGas)
    failValidation(validateMempoolTx(txNew2, blockFlow), InvalidStartGas)
    val txNew3 = tx.copy(unsigned = tx.unsigned.copy(startGas = minimalGas))
    passCheck(checkGasBound(txNew3))

    val txNew4 = tx.copy(unsigned = tx.unsigned.copy(gasPrice = GasPrice(0)))
    failCheck(checkGasBound(txNew4), InvalidGasPrice)
    val txNew5 = tx.copy(unsigned = tx.unsigned.copy(gasPrice = GasPrice(ALF.MaxALFValue)))
    failCheck(checkGasBound(txNew5), InvalidGasPrice)
  }

  it should "check ALF balance overflow" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      whenever(tx.unsigned.fixedOutputs.length >= 2) { // only able to overflow 2 outputs
        val alfAmount = tx.alfAmountInOutputs.get
        val delta     = U256.MaxValue - alfAmount + 1
        val txNew     = modifyAlfAmount(tx, delta)
        failCheck(checkOutputAmount(txNew), BalanceOverFlow)
        failValidation(validateMempoolTx(txNew, blockFlow), BalanceOverFlow)
        failCheck(checkBlockTx(txNew, preOutputs), BalanceOverFlow)
      }
    }
  }

  it should "check non-zero alf amount for outputs" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      whenever(tx.unsigned.fixedOutputs.nonEmpty) {
        val txNew = zeroAlfAmount(tx)
        failCheck(checkOutputAmount(txNew), AmountIsDustOrZero)
        failValidation(validateMempoolTx(txNew, blockFlow), AmountIsDustOrZero)
        failCheck(checkBlockTx(txNew, preOutputs), AmountIsDustOrZero)
      }
    }
  }

  it should "check non-zero token amount for outputs" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      whenever(tx.unsigned.fixedOutputs.nonEmpty) {
        val txNew = zeroTokenAmount(tx)
        failCheck(checkOutputAmount(txNew), AmountIsDustOrZero)
        failValidation(validateMempoolTx(txNew, blockFlow), AmountIsDustOrZero)
        failCheck(checkBlockTx(txNew, preOutputs), AmountIsDustOrZero)
      }
    }
  }

  it should "check the inputs indexes" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs(2, 5)) { case (tx, preOutputs) =>
      val chainIndex = tx.chainIndex
      val inputs     = tx.unsigned.inputs
      val localUnsignedGen =
        for {
          fromGroupNew <- groupIndexGen.retryUntil(!chainIndex.relateTo(_))
          scriptHint   <- scriptHintGen(fromGroupNew)
          selected     <- Gen.choose(0, inputs.length - 1)
        } yield {
          val input        = inputs(selected)
          val outputRefNew = AssetOutputRef.unsafeWithScriptHint(scriptHint, input.outputRef.key)
          val inputsNew    = inputs.replace(selected, input.copy(outputRef = outputRefNew))
          tx.unsigned.copy(inputs = inputsNew)
        }
      forAll(localUnsignedGen) { unsignedNew =>
        val txNew = tx.copy(unsigned = unsignedNew)
        failCheck(getChainIndex(txNew), InvalidInputGroupIndex)
        failValidation(validateMempoolTx(txNew, blockFlow), InvalidInputGroupIndex)
        failCheck(checkBlockTx(txNew, preOutputs), InvalidInputGroupIndex)
      }
    }
  }

  it should "check the output indexes" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs(2, 5)) { case (tx, preOutputs) =>
      val chainIndex = tx.chainIndex
      val outputs    = tx.unsigned.fixedOutputs
      whenever(
        !chainIndex.isIntraGroup && outputs.filter(_.toGroup equals chainIndex.to).length >= 2
      ) {
        val localUnsignedGen =
          for {
            toGroupNew      <- groupIndexGen.retryUntil(!chainIndex.relateTo(_))
            lockupScriptNew <- p2pkhLockupGen(toGroupNew)
            selected        <- Gen.choose(0, outputs.length - 1)
          } yield {
            val outputNew  = outputs(selected).copy(lockupScript = lockupScriptNew)
            val outputsNew = outputs.replace(selected, outputNew)
            tx.unsigned.copy(fixedOutputs = outputsNew)
          }
        forAll(localUnsignedGen) { unsignedNew =>
          val txNew = tx.copy(unsigned = unsignedNew)
          failCheck(getChainIndex(txNew), InvalidOutputGroupIndex)
          failValidation(validateMempoolTx(txNew, blockFlow), InvalidOutputGroupIndex)
          failCheck(checkBlockTx(txNew, preOutputs), InvalidOutputGroupIndex)
        }
      }
    }
  }

  it should "check distinction of inputs" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs(1, 3)) { case (tx, preOutputs) =>
      val inputs      = tx.unsigned.inputs
      val unsignedNew = tx.unsigned.copy(inputs = inputs ++ inputs)
      val txNew       = tx.copy(unsigned = unsignedNew)
      failCheck(checkUniqueInputs(txNew, checkDoubleSpending = true), TxDoubleSpending)
      failValidation(validateMempoolTx(txNew, blockFlow), TxDoubleSpending)
      failCheck(checkBlockTx(txNew, preOutputs), TxDoubleSpending)
    }
  }

  it should "check output data size" in new StatelessFixture {
    private def modifyData0(outputs: AVector[AssetOutput], index: Int): AVector[AssetOutput] = {
      val dataNew = ByteString.fromArrayUnsafe(Array.fill(ALF.MaxOutputDataSize + 1)(0))
      dataNew.length is ALF.MaxOutputDataSize + 1
      val outputNew = outputs(index).copy(additionalData = dataNew)
      outputs.replace(index, outputNew)
    }

    private def modifyData1(outputs: AVector[TxOutput], index: Int): AVector[TxOutput] = {
      val dataNew = ByteString.fromArrayUnsafe(Array.fill(ALF.MaxOutputDataSize + 1)(0))
      dataNew.length is ALF.MaxOutputDataSize + 1
      val outputNew = outputs(index) match {
        case o: AssetOutput    => o.copy(additionalData = dataNew)
        case o: ContractOutput => o
      }
      outputs.replace(index, outputNew)
    }

    forAll(transactionGenWithPreOutputs(1, 3)) { case (tx, preOutputs) =>
      val outputIndex = Random.nextInt(tx.outputsLength)
      if (tx.getOutput(outputIndex).isInstanceOf[AssetOutput]) {
        val txNew = if (outputIndex < tx.unsigned.fixedOutputs.length) {
          val outputsNew = modifyData0(tx.unsigned.fixedOutputs, outputIndex)
          tx.copy(unsigned = tx.unsigned.copy(fixedOutputs = outputsNew))
        } else {
          val correctedIndex = outputIndex - tx.unsigned.fixedOutputs.length
          val outputsNew     = modifyData1(tx.generatedOutputs, correctedIndex)
          tx.copy(generatedOutputs = outputsNew)
        }
        failCheck(checkOutputDataSize(txNew), OutputDataSizeExceeded)
        failValidation(validateMempoolTx(txNew, blockFlow), OutputDataSizeExceeded)
        failCheck(checkBlockTx(txNew, preOutputs), OutputDataSizeExceeded)
      }
    }
  }

  behavior of "stateful validation"

  trait StatefulFixture extends StatelessFixture {
    def genTokenOutput(tokenId: Hash, amount: U256): AssetOutput = {
      AssetOutput(
        U256.Zero,
        LockupScript.p2pkh(Hash.zero),
        TimeStamp.zero,
        AVector(tokenId -> amount),
        ByteString.empty
      )
    }

    def modifyTokenAmount(tx: Transaction, tokenId: TokenId, f: U256 => U256): Transaction = {
      val fixedOutputs = tx.unsigned.fixedOutputs
      val relatedOutputIndexes = fixedOutputs
        .mapWithIndex { case (output, index) =>
          (index, output.tokens.exists(_._1 equals tokenId))
        }
        .map(_._1)
      val selected    = relatedOutputIndexes.sample()
      val output      = fixedOutputs(selected)
      val tokenIndex  = output.tokens.indexWhere(_._1 equals tokenId)
      val tokenAmount = output.tokens(tokenIndex)._2
      val outputNew =
        output.copy(tokens = output.tokens.replace(tokenIndex, tokenId -> f(tokenAmount)))
      tx.copy(unsigned = tx.unsigned.copy(fixedOutputs = fixedOutputs.replace(selected, outputNew)))
    }

    def sampleToken(tx: Transaction): TokenId = {
      val tokens = tx.unsigned.fixedOutputs.flatMap(_.tokens.map(_._1))
      tokens.sample()
    }

    def getTokenAmount(tx: Transaction, tokenId: TokenId): U256 = {
      tx.unsigned.fixedOutputs.fold(U256.Zero) { case (acc, output) =>
        acc + output.tokens.filter(_._1 equals tokenId).map(_._2).reduce(_ + _)
      }
    }

    def replaceTokenId(tx: Transaction, from: TokenId, to: TokenId): Transaction = {
      val outputsNew = tx.unsigned.fixedOutputs.map { output =>
        val tokensNew = output.tokens.map {
          case (id, amount) if id equals from => (to, amount)
          case pair                           => pair
        }
        output.copy(tokens = tokensNew)
      }
      tx.copy(unsigned = tx.unsigned.copy(fixedOutputs = outputsNew))
    }
  }

  it should "get previous outputs of tx inputs" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, inputInfos) =>
      prepareWorldState(inputInfos)
      getPreOutputs(tx, cachedWorldState) isE inputInfos.map(_.referredOutput)
    }
  }

  it should "check lock time" in new StatefulFixture {
    val currentTs = TimeStamp.now()
    val futureTs  = currentTs.plusMillisUnsafe(1)
    forAll(transactionGenWithPreOutputs(lockTimeGen = Gen.const(currentTs))) {
      case (_, preOutputs) =>
        failCheck(checkLockTime(preOutputs.map(_.referredOutput), TimeStamp.zero), TimeLockedTx)
        passCheck(checkLockTime(preOutputs.map(_.referredOutput), currentTs))
        passCheck(checkLockTime(preOutputs.map(_.referredOutput), futureTs))
    }
    forAll(transactionGenWithPreOutputs(lockTimeGen = Gen.const(futureTs))) {
      case (_, preOutputs) =>
        failCheck(checkLockTime(preOutputs.map(_.referredOutput), TimeStamp.zero), TimeLockedTx)
        failCheck(checkLockTime(preOutputs.map(_.referredOutput), currentTs), TimeLockedTx)
        passCheck(checkLockTime(preOutputs.map(_.referredOutput), futureTs))
    }
  }

  it should "test both ALF and token balances" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      passCheck(checkAlfBalance(tx, preOutputs.map(_.referredOutput), None))
      passCheck(checkTokenBalance(tx, preOutputs.map(_.referredOutput)))
      passCheck(checkBlockTx(tx, preOutputs))
    }
  }

  it should "validate ALF balances" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      val txNew = modifyAlfAmount(tx, 1)
      failCheck(checkAlfBalance(txNew, preOutputs.map(_.referredOutput), None), InvalidAlfBalance)
      failCheck(checkBlockTx(txNew, preOutputs), InvalidAlfBalance)
    }
  }

  it should "test token balance overflow" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      whenever(tx.unsigned.fixedOutputs.length >= 2) { // only able to overflow 2 outputs
        val tokenId     = sampleToken(tx)
        val tokenAmount = getTokenAmount(tx, tokenId)
        val txNew       = modifyTokenAmount(tx, tokenId, U256.MaxValue - tokenAmount + 1 + _)
        failCheck(checkTokenBalance(txNew, preOutputs.map(_.referredOutput)), BalanceOverFlow)
        failCheck(checkBlockTx(txNew, preOutputs), BalanceOverFlow)
      }
    }
  }

  it should "validate token balances" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      val tokenId = sampleToken(tx)
      val txNew   = modifyTokenAmount(tx, tokenId, _ + 1)
      failCheck(checkTokenBalance(txNew, preOutputs.map(_.referredOutput)), InvalidTokenBalance)
      failCheck(checkBlockTx(txNew, preOutputs), InvalidTokenBalance)
    }
  }

  it should "validate witnesses" in new StatefulFixture {
    import ModelGenerators.ScriptPair
    forAll(transactionGenWithPreOutputs(1, 1)) { case (tx, preOutputs) =>
      val inputsState              = preOutputs.map(_.referredOutput)
      val ScriptPair(_, unlock, _) = p2pkScriptGen(GroupIndex.unsafe(1)).sample.get
      val unsigned                 = tx.unsigned
      val inputs                   = unsigned.inputs
      val preparedWorldState       = preOutputs

      {
        val txNew = tx.copy(inputSignatures = tx.inputSignatures.init)
        failCheck(checkWitnesses(txNew, inputsState), NotEnoughSignature)
        failCheck(checkBlockTx(txNew, preparedWorldState), NotEnoughSignature)
      }

      {
        val (sampleIndex, sample) = inputs.sampleWithIndex()
        val inputNew              = sample.copy(unlockScript = unlock)
        val inputsNew             = inputs.replace(sampleIndex, inputNew)
        val txNew                 = tx.copy(unsigned = unsigned.copy(inputs = inputsNew))
        failCheck(checkWitnesses(txNew, inputsState), InvalidPublicKeyHash)
      }

      {
        val signature        = Signature.generate
        val (sampleIndex, _) = tx.inputSignatures.sampleWithIndex()
        val signaturesNew    = tx.inputSignatures.replace(sampleIndex, signature)
        val txNew            = tx.copy(inputSignatures = signaturesNew)
        failCheck(checkWitnesses(txNew, inputsState), InvalidSignature)
        failCheck(checkBlockTx(txNew, preparedWorldState), InvalidSignature)
      }
    }
  }
}
