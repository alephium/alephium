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

import org.alephium.flow.{AlephiumFlowSpec, FlowFixture}
import org.alephium.flow.validation.ValidationStatus.{invalidTx, validTx}
import org.alephium.io.IOError
import org.alephium.protocol.{ALF, Hash, PrivateKey, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.model.ModelGenerators.AssetInputInfo
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{InvalidSignature => _, NetworkId => _, _}
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AVector, TimeStamp, U256}

class TxValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  override val configValues = Map(("alephium.broker.broker-num", 1))

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
        _ <- checkStateful(
          chainIndex,
          tx,
          cachedWorldState,
          preOutputs.map(_.referredOutput),
          None,
          BlockEnv(networkConfig.networkId, headerTs, Target.Max)
        )
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

  it should "check network Id" in new StatelessFixture {
    val chainIndex = chainIndexGenForBroker(brokerConfig).sample.get
    val block      = transfer(blockFlow, chainIndex)
    val tx         = block.nonCoinbase.head
    passValidation(validateTxOnlyForTest(tx, blockFlow))
    tx.unsigned.networkId isnot NetworkId.AlephiumMainNet
    val invalidTx = tx.copy(unsigned = tx.unsigned.copy(networkId = NetworkId.AlephiumMainNet))
    failValidation(validateTxOnlyForTest(invalidTx, blockFlow), InvalidNetworkId)
  }

  it should "check too many inputs" in new StatelessFixture {
    val tx    = transactionGen().sample.get
    val input = tx.unsigned.inputs.head

    val modified0 =
      tx.copy(unsigned = tx.unsigned.copy(inputs = AVector.fill(ALF.MaxTxInputNum)(input)))
    passCheck(checkInputNum(modified0, isIntraGroup = false))
    passCheck(checkInputNum(modified0, isIntraGroup = true))

    val modified1 =
      tx.copy(unsigned = tx.unsigned.copy(inputs = AVector.fill(ALF.MaxTxInputNum + 1)(input)))
    failCheck(checkInputNum(modified1, isIntraGroup = false), TooManyInputs)
    failCheck(checkInputNum(modified1, isIntraGroup = true), TooManyInputs)

    val contractOutputRef = ContractOutputRef.unsafe(Hint.unsafe(1), Hash.zero)
    val modified2         = tx.copy(contractInputs = AVector(contractOutputRef))
    passCheck(checkInputNum(modified2, isIntraGroup = true))
    failCheck(checkInputNum(modified2, isIntraGroup = false), ContractInputForInterGroupTx)
  }

  it should "check empty outputs" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs(1, 1)) { case (tx, preOutputs) =>
      val unsignedNew = tx.unsigned.copy(fixedOutputs = AVector.empty)
      val txNew       = tx.copy(unsigned = unsignedNew)
      failCheck(checkOutputNum(txNew, tx.chainIndex.isIntraGroup), NoOutputs)
      failValidation(validateTxOnlyForTest(txNew, blockFlow), NoOutputs)
      failCheck(checkBlockTx(txNew, preOutputs), NoOutputs)
    }
  }

  it should "check too many outputs" in new StatelessFixture {
    val tx     = transactionGen().sample.get
    val output = tx.unsigned.fixedOutputs.head
    tx.generatedOutputs.isEmpty is true

    val modified0 =
      tx.copy(unsigned = tx.unsigned.copy(fixedOutputs = AVector.fill(ALF.MaxTxOutputNum)(output)))
    passCheck(checkOutputNum(modified0, isIntraGroup = true))
    passCheck(checkOutputNum(modified0, isIntraGroup = false))

    val modified1 =
      tx.copy(unsigned =
        tx.unsigned.copy(fixedOutputs = AVector.fill(ALF.MaxTxOutputNum + 1)(output))
      )
    failCheck(checkOutputNum(modified1, isIntraGroup = true), TooManyOutputs)
    failCheck(checkOutputNum(modified1, isIntraGroup = false), TooManyOutputs)

    val modified2 =
      tx.copy(generatedOutputs = AVector.fill(ALF.MaxTxOutputNum - tx.outputsLength)(output))
    passCheck(checkOutputNum(modified2, isIntraGroup = true))
    failCheck(checkOutputNum(modified2, isIntraGroup = false), GeneratedOutputForInterGroupTx)

    val modified3 =
      tx.copy(generatedOutputs = AVector.fill(ALF.MaxTxOutputNum + 1 - tx.outputsLength)(output))
    failCheck(checkOutputNum(modified3, isIntraGroup = true), TooManyOutputs)
  }

  it should "check gas bounds" in new StatelessFixture {
    val tx = transactionGen(1, 1).sample.get
    passCheck(checkGasBound(tx))

    val txNew0 = tx.copy(unsigned = tx.unsigned.copy(gasAmount = GasBox.unsafeTest(-1)))
    failCheck(checkGasBound(txNew0), InvalidStartGas)
    failValidation(validateTxOnlyForTest(txNew0, blockFlow), InvalidStartGas)
    val txNew1 = tx.copy(unsigned = tx.unsigned.copy(gasAmount = GasBox.unsafeTest(0)))
    failCheck(checkGasBound(txNew1), InvalidStartGas)
    failValidation(validateTxOnlyForTest(txNew1, blockFlow), InvalidStartGas)
    val txNew2 = tx.copy(unsigned = tx.unsigned.copy(gasAmount = minimalGas.use(1).rightValue))
    failCheck(checkGasBound(txNew2), InvalidStartGas)
    failValidation(validateTxOnlyForTest(txNew2, blockFlow), InvalidStartGas)
    val txNew3 = tx.copy(unsigned = tx.unsigned.copy(gasAmount = minimalGas))
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
        failCheck(checkOutputStats(txNew), BalanceOverFlow)
        failValidation(validateTxOnlyForTest(txNew, blockFlow), BalanceOverFlow)
        failCheck(checkBlockTx(txNew, preOutputs), BalanceOverFlow)
      }
    }
  }

  it should "check non-zero alf amount for outputs" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      whenever(tx.unsigned.fixedOutputs.nonEmpty) {
        val txNew = zeroAlfAmount(tx)
        failCheck(checkOutputStats(txNew), InvalidOutputStats)
        failValidation(validateTxOnlyForTest(txNew, blockFlow), InvalidOutputStats)
        failCheck(checkBlockTx(txNew, preOutputs), InvalidOutputStats)
      }
    }
  }

  it should "check non-zero token amount for outputs" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      whenever(tx.unsigned.fixedOutputs.nonEmpty) {
        val txNew = zeroTokenAmount(tx)
        failCheck(checkOutputStats(txNew), InvalidOutputStats)
        failValidation(validateTxOnlyForTest(txNew, blockFlow), InvalidOutputStats)
        failCheck(checkBlockTx(txNew, preOutputs), InvalidOutputStats)
      }
    }
  }

  it should "check the number of tokens for outputs" in new StatelessFixture {
    val tx0 =
      transactionGen(minTokens = maxTokenPerUtxo + 1, maxTokens = maxTokenPerUtxo + 1).sample.get
    failCheck(checkOutputStats(tx0), InvalidOutputStats)
    val tx1 =
      transactionGen(minTokens = maxTokenPerUtxo, maxTokens = maxTokenPerUtxo).sample.get
    passCheck(checkOutputStats(tx1))
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
        failValidation(validateTxOnlyForTest(txNew, blockFlow), InvalidInputGroupIndex)
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
            lockupScriptNew <- assetLockupGen(toGroupNew)
            selected        <- Gen.choose(0, outputs.length - 1)
          } yield {
            val outputNew  = outputs(selected).copy(lockupScript = lockupScriptNew)
            val outputsNew = outputs.replace(selected, outputNew)
            tx.unsigned.copy(fixedOutputs = outputsNew)
          }
        forAll(localUnsignedGen) { unsignedNew =>
          val txNew = tx.copy(unsigned = unsignedNew)
          failCheck(getChainIndex(txNew), InvalidOutputGroupIndex)
          failValidation(validateTxOnlyForTest(txNew, blockFlow), InvalidOutputGroupIndex)
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
      failValidation(validateTxOnlyForTest(txNew, blockFlow), TxDoubleSpending)
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
        failValidation(validateTxOnlyForTest(txNew, blockFlow), OutputDataSizeExceeded)
        failCheck(checkBlockTx(txNew, preOutputs), OutputDataSizeExceeded)
      }
    }
  }

  behavior of "stateful validation"

  trait StatefulFixture extends StatelessFixture {
    def getPreOutputs(
        tx: Transaction,
        worldState: MutableWorldState
    ): TxValidationResult[AVector[TxOutput]] = {
      worldState.getPreOutputs(tx) match {
        case Right(preOutputs)            => validTx(preOutputs)
        case Left(IOError.KeyNotFound(_)) => invalidTx(NonExistInput)
        case Left(error)                  => Left(Left(error))
      }
    }

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

    def checkWitnesses(
        tx: Transaction,
        preOutputs: AVector[TxOutput]
    ): TxValidationResult[GasBox] = {
      checkGasAndWitnesses(
        tx,
        preOutputs,
        BlockEnv(networkConfig.networkId, TimeStamp.now(), Target.Max)
      )
    }
  }

  it should "get previous outputs of tx inputs" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, inputInfos) =>
      prepareWorldState(inputInfos)
      getPreOutputs(tx, cachedWorldState) isE inputInfos.map(_.referredOutput).as[TxOutput]
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

  it should "check the exact gas cost" in new StatefulFixture {
    import GasSchedule._

    val chainIndex  = chainIndexGenForBroker(brokerConfig).sample.get
    val block       = transfer(blockFlow, chainIndex)
    val tx          = block.nonCoinbase.head
    val blockEnv    = BlockEnv.from(block.header)
    val worldState  = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
    val prevOutputs = worldState.getPreOutputs(tx).rightValue

    val initialGas = tx.unsigned.gasAmount
    val gasLeft    = checkGasAndWitnesses(tx, prevOutputs, blockEnv).rightValue
    val gasUsed    = initialGas.use(gasLeft).rightValue
    gasUsed is GasBox.unsafe(14060)
    gasUsed is (txBaseGas addUnsafe txInputBaseGas addUnsafe txOutputBaseGas.mulUnsafe(
      2
    ) addUnsafe GasSchedule.p2pkUnlockGas)
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
        failCheck(checkWitnesses(txNew, inputsState.as[TxOutput]), NotEnoughSignature)
        failCheck(checkBlockTx(txNew, preparedWorldState), NotEnoughSignature)
      }

      {
        val (sampleIndex, sample) = inputs.sampleWithIndex()
        val inputNew              = sample.copy(unlockScript = unlock)
        val inputsNew             = inputs.replace(sampleIndex, inputNew)
        val txNew                 = tx.copy(unsigned = unsigned.copy(inputs = inputsNew))
        failCheck(checkWitnesses(txNew, inputsState.as[TxOutput]), InvalidPublicKeyHash)
      }

      {
        val signature        = Signature.generate
        val (sampleIndex, _) = tx.inputSignatures.sampleWithIndex()
        val signaturesNew    = tx.inputSignatures.replace(sampleIndex, signature)
        val txNew            = tx.copy(inputSignatures = signaturesNew)
        failCheck(checkWitnesses(txNew, inputsState.as[TxOutput]), InvalidSignature)
        failCheck(checkBlockTx(txNew, preparedWorldState), InvalidSignature)
      }

      {
        val txNew = tx.copy(inputSignatures = tx.inputSignatures ++ tx.inputSignatures)
        failCheck(checkWitnesses(txNew, inputsState.as[TxOutput]), TooManySignature)
      }
    }
  }

  it should "compress signatures" in new StatefulFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val tx         = transfer(blockFlow, chainIndex).nonCoinbase.head
    val unsigned1  = tx.unsigned.copy(inputs = tx.unsigned.inputs ++ tx.unsigned.inputs)
    val tx1        = Transaction.from(unsigned1, genesisKeys(0)._1)
    val preOutputs =
      blockFlow
        .getBestPersistedWorldState(chainIndex.from)
        .rightValue
        .getPreOutputs(tx1)
        .rightValue
    tx1.unsigned.inputs.length is 2
    tx1.inputSignatures.length is 1
    passCheck(checkWitnesses(tx1, preOutputs))
    val tx2 = tx1.copy(inputSignatures = tx1.inputSignatures ++ tx1.inputSignatures)
    tx2.unsigned.inputs.length is 2
    tx2.inputSignatures.length is 2
    failCheck(checkWitnesses(tx2, preOutputs), TooManySignature)
  }

  behavior of "lockup script"

  trait LockupFixture extends StatefulFixture {
    def prepareOutput(lockup: LockupScript.Asset, unlock: UnlockScript) = {
      val group                 = lockup.groupIndex
      val (genesisPriKey, _, _) = genesisKeys(group.value)
      val block                 = transfer(blockFlow, genesisPriKey, lockup, ALF.alf(2))
      addAndCheck(blockFlow, block)
      blockFlow
        .transfer(
          lockup,
          unlock,
          AVector(TxOutputInfo(lockup, ALF.alf(1), AVector.empty, None)),
          None,
          defaultGasPrice
        )
        .rightValue
        .rightValue
    }

    def replaceUnlock(tx: Transaction, unlock: UnlockScript, priKeys: PrivateKey*): Transaction = {
      val unsigned  = tx.unsigned
      val inputs    = unsigned.inputs
      val theInput  = inputs.head
      val newInputs = inputs.replace(0, theInput.copy(unlockScript = unlock))
      val newTx     = tx.copy(unsigned = unsigned.copy(inputs = newInputs))
      if (priKeys.isEmpty) {
        newTx
      } else {
        sign(newTx.unsigned, priKeys: _*)
      }
    }

    def sign(unsigned: UnsignedTransaction, privateKeys: PrivateKey*): Transaction = {
      val signatures = privateKeys.map(SignatureSchema.sign(unsigned.hash.bytes, _))
      Transaction.from(unsigned, AVector.from(signatures))
    }
  }

  it should "validate p2pkh" in new LockupFixture {
    val (priKey, pubKey) = keypairGen.sample.get
    val lockup           = LockupScript.p2pkh(pubKey)
    val unlock           = UnlockScript.p2pkh(pubKey)

    val unsigned = prepareOutput(lockup, unlock)
    val tx0      = Transaction.from(unsigned, priKey)
    passValidation(validateTxOnlyForTest(tx0, blockFlow))
    val tx1 = replaceUnlock(tx0, UnlockScript.p2pkh(PublicKey.generate))
    failValidation(validateTxOnlyForTest(tx1, blockFlow), InvalidPublicKeyHash)
    val tx2 = tx0.copy(inputSignatures = AVector(Signature.generate))
    failValidation(validateTxOnlyForTest(tx2, blockFlow), InvalidSignature)
  }

  it should "invalidate p2mpkh" in new LockupFixture {
    val (priKey0, pubKey0) = keypairGen.sample.get
    val (priKey1, pubKey1) = keypairGen.sample.get
    val (_, pubKey2)       = keypairGen.sample.get

    def test(expected: Option[InvalidTxStatus], keys: (PublicKey, Int)*) = {
      val lockup   = LockupScript.p2mpkhUnsafe(AVector(pubKey0, pubKey1, pubKey2), 2)
      val unlock   = UnlockScript.p2mpkh(AVector.from(keys))
      val unsigned = prepareOutput(lockup, unlock)
      val tx       = sign(unsigned, priKey0, priKey1)
      val result   = validateTxOnlyForTest(tx, blockFlow)
      expected match {
        case Some(error) => result.leftValue isE error
        case None        => result.isRight is true
      }
    }

    test(Some(InvalidNumberOfPublicKey), pubKey0  -> 0)
    test(Some(InvalidNumberOfPublicKey), pubKey0  -> 0, pubKey1 -> 1, pubKey2 -> 2)
    test(Some(InvalidP2mpkhUnlockScript), pubKey1 -> 1, pubKey0 -> 0)
    test(Some(InvalidP2mpkhUnlockScript), pubKey1 -> 0, pubKey0 -> 0)
    test(Some(InvalidP2mpkhUnlockScript), pubKey1 -> 1, pubKey0 -> 1)
    test(Some(InvalidP2mpkhUnlockScript), pubKey0 -> 0, pubKey1 -> 3)
    test(Some(InvalidPublicKeyHash), pubKey0      -> 0, pubKey1 -> 2)
    test(Some(InvalidPublicKeyHash), pubKey1      -> 0, pubKey1 -> 1)
    test(Some(InvalidSignature), pubKey0          -> 0, pubKey2 -> 2)
    test(None, pubKey0                            -> 0, pubKey1 -> 1)
  }

  it should "validate p2sh" in new LockupFixture {
    // scalastyle:off no.equal
    def rawScript(n: Int) =
      s"""
         |AssetScript P2sh {
         |  pub fn main(a: U256) -> () {
         |    assert!(a == $n)
         |  }
         |}
         |""".stripMargin
    // scalastyle:on no.equal

    val script   = Compiler.compileAssetScript(rawScript(51)).rightValue
    val lockup   = LockupScript.p2sh(script)
    val unlock   = UnlockScript.p2sh(script, AVector(Val.U256(51)))
    val unsigned = prepareOutput(lockup, unlock)

    val tx0 = Transaction.from(unsigned, AVector.empty[Signature])
    passValidation(validateTxOnlyForTest(tx0, blockFlow))
    val tx1 = replaceUnlock(tx0, UnlockScript.p2sh(script, AVector(Val.U256(50))))
    failValidation(validateTxOnlyForTest(tx1, blockFlow), UnlockScriptExeFailed(AssertionFailed))
    val newScript = Compiler.compileAssetScript(rawScript(50)).rightValue
    val tx2       = replaceUnlock(tx0, UnlockScript.p2sh(newScript, AVector(Val.U256(50))))
    failValidation(validateTxOnlyForTest(tx2, blockFlow), InvalidScriptHash)
  }

  trait GasFixture extends LockupFixture {
    def groupIndex: GroupIndex
    def tx: Transaction
    lazy val initialGas = minimalGas
    lazy val blockEnv =
      BlockEnv(NetworkId.AlephiumMainNet, TimeStamp.now(), consensusConfig.maxMiningTarget)
    lazy val prevOutputs = blockFlow
      .getBestPersistedWorldState(groupIndex)
      .rightValue
      .getPreOutputs(tx)
      .rightValue
      .asUnsafe[AssetOutput]
    lazy val txEnv = TxEnv(tx, prevOutputs, Stack.ofCapacity[Signature](0))
  }

  it should "charge gas for asset script size" in new GasFixture {
    val rawScript =
      s"""
         |AssetScript P2sh {
         |  pub fn main() -> () {
         |    return
         |  }
         |}
         |""".stripMargin

    val script   = Compiler.compileAssetScript(rawScript).rightValue
    val lockup   = LockupScript.p2sh(script)
    val unlock   = UnlockScript.p2sh(script, AVector(Val.U256(51)))
    val unsigned = prepareOutput(lockup, unlock)
    val tx       = Transaction.from(unsigned, AVector.empty[Signature])

    val groupIndex = lockup.groupIndex
    val gasRemaining =
      checkUnlockScript(blockEnv, txEnv, initialGas, script.hash, script, AVector.empty).rightValue
    initialGas is gasRemaining.addUnsafe(
      script.bytes.size + GasHash.gas(script.bytes.size).value + 200 /* 200 is the call gas */
    )
  }

  it should "charge gas for tx script size" in new GasFixture {
    val rawScript =
      s"""
         |TxScript P2sh {
         |  pub fn main() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val script     = Compiler.compileTxScript(rawScript).rightValue
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = simpleScript(blockFlow, chainIndex, script)
    val tx         = block.nonCoinbase.head
    val groupIndex = GroupIndex.unsafe(0)

    val worldState = blockFlow.getBestCachedWorldState(groupIndex).rightValue
    val gasRemaining =
      checkTxScript(chainIndex, tx, initialGas, worldState, prevOutputs, blockEnv).rightValue
    initialGas is gasRemaining.addUnsafe(script.bytes.size + 200 /* 200 is the call gas */ )
    val noScriptTx = tx.copy(unsigned = tx.unsigned.copy(scriptOpt = None))
    checkTxScript(
      chainIndex,
      noScriptTx,
      initialGas,
      worldState,
      prevOutputs,
      blockEnv
    ).rightValue is initialGas
  }

  it should "validate mempool tx fully" in new FlowFixture {
    val txValidator = TxValidation.build
    txValidator
      .validateMempoolTxTemplate(outOfGasTxTemplate, blockFlow)
      .leftValue isE TxScriptExeFailed(OutOfGas)
  }
}
