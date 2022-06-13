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
import org.alephium.protocol.{ALPH, Hash, PrivateKey, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.model.ModelGenerators.AssetInputInfo
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{InvalidSignature => _, NetworkId => _, _}
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AVector, TimeStamp, U256}

// scalastyle:off number.of.methods file.size.limit
class TxValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  override val configValues = Map(("alephium.broker.broker-num", 1))

  type TxValidator[T] = Transaction => TxValidationResult[T]

  trait Fixture extends TxValidation.Impl with VMFactory {
    val blockFlow = genesisBlockFlow()
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
        _          <- checkStateless(chainIndex, tx, checkDoubleSpending = true, HardFork.Leman)
        _ <- checkStateful(
          chainIndex,
          tx,
          cachedWorldState,
          preOutputs.map(_.referredOutput),
          None,
          BlockEnv(networkConfig.networkId, headerTs, Target.Max, None)
        )
      } yield ()
    }

    def checkWitnesses(
        tx: Transaction,
        preOutputs: AVector[TxOutput]
    ): TxValidationResult[GasBox] = {
      val blockEnv = BlockEnv(networkConfig.networkId, TimeStamp.now(), Target.Max, None)
      checkGasAndWitnesses(tx, preOutputs, blockEnv)
    }

    def prepareOutput(lockup: LockupScript.Asset, unlock: UnlockScript) = {
      val group                 = lockup.groupIndex
      val (genesisPriKey, _, _) = genesisKeys(group.value)
      val block                 = transfer(blockFlow, genesisPriKey, lockup, ALPH.alph(2))
      val output                = AVector(TxOutputInfo(lockup, ALPH.alph(1), AVector.empty, None))
      addAndCheck(blockFlow, block)

      blockFlow
        .transfer(
          lockup,
          unlock,
          output,
          None,
          defaultGasPrice,
          defaultUtxoLimit
        )
        .rightValue
        .rightValue
    }

    def sign(unsigned: UnsignedTransaction, privateKeys: PrivateKey*): Transaction = {
      val signatures = privateKeys.map(SignatureSchema.sign(unsigned.hash.bytes, _))
      Transaction.from(unsigned, AVector.from(signatures))
    }

    def nestedValidator[T](
        validator: TxValidator[T],
        preOutputs: AVector[AssetInputInfo]
    ): TxValidator[T] = (transaction: Transaction) => {
      for {
        result <- nestedValidator(validator)(transaction)
        _      <- checkBlockTx(transaction, preOutputs)
      } yield result
    }

    def nestedValidator[T](validator: TxValidator[T]): TxValidator[T] =
      (transaction: Transaction) => {
        for {
          result <- validator(transaction)
          _      <- validateTxOnlyForTest(transaction, blockFlow)
          _      <- validateMempoolTxTemplate(transaction.toTemplate, blockFlow)
        } yield result
      }

    implicit class RichTxValidationResult[T](res: TxValidationResult[T]) {
      def pass()                       = res.isRight is true
      def fail(error: InvalidTxStatus) = res.left.value isE error
    }

    implicit class RichTx(tx: Transaction) {
      def addAttoAlphAmount(delta: U256): Transaction = {
        updateAttoAlphAmount(_ + delta)
      }

      def zeroAttoAlphAmount(): Transaction = {
        updateAttoAlphAmount(_ => 0)
      }

      def updateAttoAlphAmount(f: U256 => U256): Transaction = {
        updateRandomFixedOutputs(output => output.copy(amount = f(output.amount)))
      }

      def zeroTokenAmount(): Transaction = {
        updateRandomFixedOutputs(_.copy(tokens = AVector(Hash.generate -> U256.Zero)))
      }

      def inputs(inputs: AVector[TxInput]): Transaction = {
        tx.updateUnsigned(_.copy(inputs = inputs))
      }

      def gasPrice(gasPrice: GasPrice): Transaction = {
        tx.updateUnsigned(_.copy(gasPrice = gasPrice))
      }

      def gasAmount(gasAmount: GasBox): Transaction = {
        tx.updateUnsigned(_.copy(gasAmount = gasAmount))
      }

      def fixedOutputs(outputs: AVector[AssetOutput]): Transaction = {
        tx.updateUnsigned(_.copy(fixedOutputs = outputs))
      }

      def getTokenAmount(tokenId: TokenId): U256 = {
        tx.unsigned.fixedOutputs.fold(U256.Zero) { case (acc, output) =>
          acc + output.tokens.filter(_._1 equals tokenId).map(_._2).reduce(_ + _)
        }
      }

      def updateUnsigned(f: UnsignedTransaction => UnsignedTransaction): Transaction = {
        tx.copy(unsigned = f(tx.unsigned))
      }

      def updateRandomFixedOutputs(f: AssetOutput => AssetOutput): Transaction = {
        val (index, output) = tx.unsigned.fixedOutputs.sampleWithIndex()
        val outputNew       = f(output)
        tx.updateUnsigned(_.copy(fixedOutputs = tx.unsigned.fixedOutputs.replace(index, outputNew)))
      }

      def updateRandomGeneratedOutputs(f: TxOutput => TxOutput): Transaction = {
        val (index, output) = tx.generatedOutputs.sampleWithIndex()
        val outputNew       = f(output)
        tx.copy(generatedOutputs = tx.generatedOutputs.replace(index, outputNew))
      }

      def updateRandomInputs(f: TxInput => TxInput): Transaction = {
        val (index, input) = tx.unsigned.inputs.sampleWithIndex()
        val inputNew       = f(input)
        tx.updateUnsigned(_.copy(inputs = tx.unsigned.inputs.replace(index, inputNew)))
      }

      def sampleToken(): TokenId = {
        val tokens = tx.unsigned.fixedOutputs.flatMap(_.tokens.map(_._1))
        tokens.sample()
      }

      def getPreOutputs(
          worldState: MutableWorldState
      ): TxValidationResult[AVector[TxOutput]] = {
        worldState.getPreOutputs(tx) match {
          case Right(preOutputs)            => validTx(preOutputs)
          case Left(IOError.KeyNotFound(_)) => invalidTx(NonExistInput)
          case Left(error)                  => Left(Left(error))
        }
      }

      def modifyTokenAmount(tokenId: TokenId, f: U256 => U256): Transaction = {
        val fixedOutputs = tx.unsigned.fixedOutputs
        val (output, selected) = fixedOutputs.zipWithIndex
          .filter {
            _._1.tokens.exists(_._1 equals tokenId)
          }
          .sample()
        val tokenIndex  = output.tokens.indexWhere(_._1 equals tokenId)
        val tokenAmount = output.tokens(tokenIndex)._2
        val newTokens   = output.tokens.replace(tokenIndex, tokenId -> f(tokenAmount))
        val newOutput   = output.copy(tokens = newTokens)
        val newOutputs  = fixedOutputs.replace(selected, newOutput)
        tx.updateUnsigned(_.copy(fixedOutputs = newOutputs))
      }

      def replaceUnlock(unlock: UnlockScript, priKeys: PrivateKey*): Transaction = {
        val unsigned  = tx.unsigned
        val inputs    = unsigned.inputs
        val theInput  = inputs.head
        val newInputs = inputs.replace(0, theInput.copy(unlockScript = unlock))
        val newTx     = tx.updateUnsigned(_.copy(inputs = newInputs))
        if (priKeys.isEmpty) {
          newTx
        } else {
          sign(newTx.unsigned, priKeys: _*)
        }
      }

      def pass[T]()(implicit validator: TxValidator[T]): Assertion = {
        validator(tx).pass()
      }

      def fail[T](error: InvalidTxStatus)(implicit validator: TxValidator[T]): Assertion = {
        validator(tx).fail(error)
      }
    }
  }

  it should "pass valid transactions" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      checkBlockTx(tx, preOutputs).pass()
    }
  }

  behavior of "Stateless Validation"

  it should "check tx version" in new Fixture {
    implicit val validator = nestedValidator(checkVersion)

    val chainIndex = chainIndexGenForBroker(brokerConfig).sample.value
    val block      = transfer(blockFlow, chainIndex)
    val tx         = block.nonCoinbase.head
    tx.pass()

    tx.unsigned.version is DefaultTxVersion
    tx.unsigned.version isnot Byte.MaxValue
    val invalidTx = tx.updateUnsigned(_.copy(version = Byte.MaxValue))
    invalidTx.fail(InvalidTxVersion)
  }

  it should "check network Id" in new Fixture {
    implicit val validator = nestedValidator(checkVersion)

    val chainIndex = chainIndexGenForBroker(brokerConfig).sample.value
    val block      = transfer(blockFlow, chainIndex)
    val tx         = block.nonCoinbase.head
    tx.pass()

    tx.unsigned.networkId isnot NetworkId.AlephiumMainNet
    val invalidTx = tx.updateUnsigned(_.copy(networkId = NetworkId.AlephiumMainNet))
    invalidTx.fail(InvalidNetworkId)
  }

  it should "check too many inputs" in new Fixture {
    val tx    = transactionGen().sample.get
    val input = tx.unsigned.inputs.head

    val modified0         = tx.inputs(AVector.fill(ALPH.MaxTxInputNum)(input))
    val modified1         = tx.inputs(AVector.fill(ALPH.MaxTxInputNum + 1)(input))
    val contractOutputRef = ContractOutputRef.unsafe(Hint.unsafe(1), Hash.zero)
    val modified2         = tx.copy(contractInputs = AVector(contractOutputRef))
    val modified3 =
      tx.copy(contractInputs = AVector.fill(ALPH.MaxTxInputNum + 1)(contractOutputRef))

    {
      implicit val validator = checkInputNum(_, isIntraGroup = false)

      modified0.pass()
      modified1.fail(TooManyInputs)
      modified2.fail(ContractInputForInterGroupTx)
      modified3.fail(ContractInputForInterGroupTx)
    }

    {
      implicit val validator = checkInputNum(_, isIntraGroup = true)

      modified0.pass()
      modified1.fail(TooManyInputs)
      modified2.pass()
      modified3.fail(TooManyInputs)
    }
  }

  it should "check empty outputs" in new Fixture {
    forAll(transactionGenWithPreOutputs(1, 1)) { case (tx, preOutputs) =>
      implicit val validator =
        nestedValidator(checkOutputNum(_, tx.chainIndex.isIntraGroup), preOutputs)

      tx.updateUnsigned(_.copy(fixedOutputs = AVector.empty)).fail(NoOutputs)
    }
  }

  it should "check too many outputs" in new Fixture {
    val tx     = transactionGen().sample.get
    val output = tx.unsigned.fixedOutputs.head
    tx.generatedOutputs.isEmpty is true

    val maxGeneratedOutputsNum = ALPH.MaxTxOutputNum - tx.outputsLength

    val modified0 = tx.fixedOutputs(AVector.fill(ALPH.MaxTxOutputNum)(output))
    val modified1 = tx.fixedOutputs(AVector.fill(ALPH.MaxTxOutputNum + 1)(output))
    val modified2 = tx.copy(generatedOutputs = AVector.fill(maxGeneratedOutputsNum)(output))
    val modified3 = tx.copy(generatedOutputs = AVector.fill(maxGeneratedOutputsNum + 1)(output))

    {
      implicit val validator = checkOutputNum(_, isIntraGroup = true)

      modified0.pass()
      modified1.fail(TooManyOutputs)
      modified2.pass()
      modified3.fail(TooManyOutputs)
    }

    {
      implicit val validator = checkOutputNum(_, isIntraGroup = false)

      modified0.pass()
      modified1.fail(TooManyOutputs)
      modified2.fail(GeneratedOutputForInterGroupTx)
      modified3.fail(GeneratedOutputForInterGroupTx)
    }
  }

  it should "check the number of script signatures" in new Fixture {
    val tx        = transactionGen().sample.get
    val signature = Signature.generate
    val modified0 = tx.copy(scriptSignatures = AVector.empty)
    val modified1 = tx.copy(scriptSignatures = AVector.fill(ALPH.MaxScriptSigNum)(signature))
    val modified2 = tx.copy(scriptSignatures = AVector.fill(ALPH.MaxScriptSigNum + 1)(signature))

    {
      implicit val validator = checkScriptSigNum(_, isIntraGroup = true)
      modified0.pass()
      modified1.pass()
      modified2.fail(TooManyScriptSignatures)
    }

    {
      implicit val validator = checkScriptSigNum(_, isIntraGroup = false)
      modified0.pass()
      modified1.fail(UnexpectedScriptSignatures)
      modified2.fail(UnexpectedScriptSignatures)
    }
  }

  it should "check gas bounds" in new Fixture {
    implicit val validator = checkGasBound(_)

    val tx = transactionGen(1, 1).sample.value
    tx.pass()

    tx.gasAmount(GasBox.unsafeTest(-1)).fail(InvalidStartGas)
    tx.gasAmount(GasBox.unsafeTest(0)).fail(InvalidStartGas)
    tx.gasAmount(minimalGas).pass()
    tx.gasAmount(minimalGas.use(1).rightValue).fail(InvalidStartGas)
    tx.gasAmount(maximalGasPerTx).pass()
    tx.gasAmount(maximalGasPerTx.addUnsafe(1)).fail(InvalidStartGas)

    tx.gasPrice(GasPrice(0)).fail(InvalidGasPrice)
    tx.gasPrice(minimalGasPrice).pass()
    tx.gasPrice(GasPrice(minimalGasPrice.value - 1)).fail(InvalidGasPrice)
    tx.gasPrice(GasPrice(ALPH.MaxALPHValue - 1)).pass()
    tx.gasPrice(GasPrice(ALPH.MaxALPHValue)).fail(InvalidGasPrice)
  }

  it should "check ALPH balance stats" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, _) =>
      implicit val validator = checkOutputStats(_, HardFork.Leman)

      // balance overflow
      val attoAlphAmount = tx.attoAlphAmountInOutputs.value
      val delta          = U256.MaxValue - attoAlphAmount + 1
      tx.addAttoAlphAmount(delta).fail(BalanceOverFlow)

      // zero amount
      tx.zeroAttoAlphAmount().fail(InvalidOutputStats)

      {
        info("Check dust amount before leman")
        implicit val validator = checkOutputStats(_, HardFork.Mainnet)
        tx.updateAttoAlphAmount(_ => deprecatedDustUtxoAmount).pass()
        tx.updateAttoAlphAmount(_ => deprecatedDustUtxoAmount - 1).fail(InvalidOutputStats)
      }

      {
        info("Check dust amount for leman")
        dustUtxoAmount is (deprecatedDustUtxoAmount * 1000)
        implicit val validator = checkOutputStats(_, HardFork.Leman)
        tx.updateAttoAlphAmount(_ => dustUtxoAmount).pass()
        tx.updateAttoAlphAmount(_ => dustUtxoAmount - 1).fail(InvalidOutputStats)
      }
    }
  }

  it should "check non-zero token amount for outputs" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      whenever(tx.unsigned.fixedOutputs.nonEmpty) {
        implicit val validator = nestedValidator(checkOutputStats(_, HardFork.Leman), preOutputs)

        tx.zeroTokenAmount().fail(InvalidOutputStats)
      }
    }
  }

  it should "check the number of tokens for outputs" in new Fixture {
    {
      info("Leman hardfork")
      implicit val validator = checkOutputStats(_, HardFork.Leman)

      forAll(transactionGen(numTokensGen = maxTokenPerUtxo + 1))(_.fail(InvalidOutputStats))
      forAll(transactionGen(numTokensGen = maxTokenPerUtxo))(_.pass())
    }

    {
      info("Pre-Leman hardfork")
      implicit val validator = checkOutputStats(_, HardFork.Mainnet)

      forAll(transactionGen(numTokensGen = deprecatedMaxTokenPerUtxo + 1))(
        _.fail(InvalidOutputStats)
      )
      forAll(transactionGen(numTokensGen = deprecatedMaxTokenPerUtxo))(_.pass())
    }
  }

  it should "check the inputs indexes" in new Fixture {
    forAll(transactionGenWithPreOutputs(inputsNumGen = Gen.choose(2, 10))) {
      case (tx, preOutputs) =>
        implicit val validator = nestedValidator(getChainIndex, preOutputs)

        val chainIndex = tx.chainIndex
        val invalidTxGen = for {
          fromGroupNew <- groupIndexGen.retryUntil(!chainIndex.relateTo(_))
          scriptHint   <- scriptHintGen(fromGroupNew)
        } yield {
          tx.updateRandomInputs { input =>
            val outputRefNew = AssetOutputRef.unsafeWithScriptHint(scriptHint, input.outputRef.key)
            input.copy(outputRef = outputRefNew)
          }
        }
        forAll(invalidTxGen)(_.fail(InvalidInputGroupIndex))
    }
  }

  it should "check the output indexes" in new Fixture {
    forAll(transactionGenWithPreOutputs(inputsNumGen = Gen.choose(2, 10))) {
      case (tx, preOutputs) =>
        val chainIndex = tx.chainIndex
        whenever(!chainIndex.isIntraGroup) {
          implicit val validator = nestedValidator(getChainIndex, preOutputs)

          val invalidTxGen = for {
            toGroup1 <- groupIndexGen.retryUntil(chainIndex.from != _)
            toGroup2 <- groupIndexGen.retryUntil { index =>
              (chainIndex.from != index) && (toGroup1 != index)
            }
            assetOutput1 <- assetOutputGen(toGroup1)()
            assetOutput2 <- assetOutputGen(toGroup2)()
          } yield {
            tx.updateUnsigned(_.copy(fixedOutputs = AVector(assetOutput1, assetOutput2)))
          }
          forAll(invalidTxGen)(_.fail(InvalidOutputGroupIndex))
        }
    }
  }

  it should "check distinction of inputs" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      val validator = nestedValidator(checkUniqueInputs(_, true), preOutputs)

      val inputs    = tx.unsigned.inputs
      val invalidTx = tx.updateUnsigned(_.copy(inputs = inputs ++ inputs))
      invalidTx.pass()(checkUniqueInputs(_, false))
      invalidTx.fail(TxDoubleSpending)(validator)
    }
  }

  it should "check output data size" in new Fixture {
    val oversizedData = ByteString.fromArrayUnsafe(Array.fill(ALPH.MaxOutputDataSize + 1)(0))
    oversizedData.length is ALPH.MaxOutputDataSize + 1

    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      implicit val validator = nestedValidator(checkOutputDataSize, preOutputs)

      val updateFixedOutput = Random.nextInt(tx.outputsLength) < tx.unsigned.fixedOutputs.length
      val txNew = if (updateFixedOutput) {
        tx.updateRandomFixedOutputs(_.copy(additionalData = oversizedData))
      } else {
        tx.updateRandomGeneratedOutputs {
          case o: AssetOutput    => o.copy(additionalData = oversizedData)
          case o: ContractOutput => o
        }
      }

      txNew.fail(OutputDataSizeExceeded)
    }
  }

  behavior of "stateful validation"

  it should "get previous outputs of tx inputs" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, inputInfos) =>
      prepareWorldState(inputInfos)
      tx.getPreOutputs(cachedWorldState) isE inputInfos.map(_.referredOutput).as[TxOutput]
    }
  }

  it should "check lock time" in new Fixture {
    val currentTs = TimeStamp.now()
    val futureTs  = currentTs.plusMillisUnsafe(1)
    forAll(transactionGenWithPreOutputs(lockTimeGen = Gen.const(currentTs))) {
      case (_, preOutputs) =>
        checkLockTime(preOutputs.map(_.referredOutput), TimeStamp.zero).fail(TimeLockedTx)
        checkLockTime(preOutputs.map(_.referredOutput), currentTs).pass()
        checkLockTime(preOutputs.map(_.referredOutput), futureTs).pass()
    }
    forAll(transactionGenWithPreOutputs(lockTimeGen = Gen.const(futureTs))) {
      case (_, preOutputs) =>
        checkLockTime(preOutputs.map(_.referredOutput), TimeStamp.zero).fail(TimeLockedTx)
        checkLockTime(preOutputs.map(_.referredOutput), currentTs).fail(TimeLockedTx)
        checkLockTime(preOutputs.map(_.referredOutput), futureTs).pass()
    }
  }

  it should "test both ALPH and token balances" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      checkAlphBalance(tx, preOutputs.map(_.referredOutput), None).pass()
      checkTokenBalance(tx, preOutputs.map(_.referredOutput)).pass()
      checkBlockTx(tx, preOutputs).pass()
    }
  }

  it should "validate ALPH balances" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      implicit val validator = nestedValidator(
        checkAlphBalance(_, preOutputs.map(_.referredOutput), None),
        preOutputs
      )

      tx.addAttoAlphAmount(1).fail(InvalidAlphBalance)
    }
  }

  it should "test token balance overflow" in new Fixture {
    forAll(transactionGenWithPreOutputs(tokensNumGen = Gen.choose(1, 10))) {
      case (tx, preOutputs) =>
        implicit val validator = nestedValidator(
          checkTokenBalance(_, preOutputs.map(_.referredOutput)),
          preOutputs
        )

        whenever(tx.unsigned.fixedOutputs.length >= 2) { // only able to overflow 2 outputs
          val tokenId     = tx.sampleToken()
          val tokenAmount = tx.getTokenAmount(tokenId)

          tx.modifyTokenAmount(tokenId, U256.MaxValue - tokenAmount + 1 + _).fail(BalanceOverFlow)
        }
    }
  }

  it should "validate token balances" in new Fixture {
    forAll(transactionGenWithPreOutputs(tokensNumGen = Gen.choose(1, 10)), Gen.prob(0.5)) {
      case ((tx, preOutputs), useAssets) =>
        implicit val validator = nestedValidator(
          checkTokenBalance(_, preOutputs.map(_.referredOutput)),
          preOutputs
        )

        val tokenId   = tx.sampleToken()
        val invalidTx = tx.modifyTokenAmount(tokenId, _ + 1)
        invalidTx.fail(InvalidTokenBalance)

        val invalidTxWithScript = {
          val method = Method[StatefulContext](
            isPublic = true,
            usePreapprovedAssets = useAssets,
            useContractAssets = useAssets,
            argsLength = 0,
            localsLength = 0,
            returnLength = 0,
            instrs = AVector.empty
          )
          val script = StatefulScript.unsafe(AVector(method))

          invalidTx.updateUnsigned(_.copy(scriptOpt = Some(script)))
        }

        if (!useAssets) {
          invalidTxWithScript.fail(InvalidTokenBalance)
        }
    }
  }

  it should "check the exact gas cost" in new Fixture {
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

    tx.unsigned.inputs.length is 1
    tx.unsigned.inputs(0).unlockScript is a[UnlockScript.P2PKH]
    tx.unsigned.fixedOutputs.length is 2

    gasUsed is (
      txBaseGas addUnsafe
        txInputBaseGas addUnsafe
        txOutputBaseGas.mulUnsafe(2) addUnsafe
        GasSchedule.p2pkUnlockGas
    )
    gasUsed is GasBox.unsafe(14060)
  }

  it should "validate witnesses" in new Fixture {
    import ModelGenerators.ScriptPair
    forAll(transactionGenWithPreOutputs(1, 1)) { case (tx, preOutputs) =>
      val inputsState              = preOutputs.map(_.referredOutput).as[TxOutput]
      val ScriptPair(_, unlock, _) = p2pkScriptGen(GroupIndex.unsafe(1)).sample.get

      implicit val validator = nestedValidator(checkWitnesses(_, inputsState), preOutputs)

      tx.updateRandomInputs(_.copy(unlockScript = unlock)).fail(InvalidPublicKeyHash)

      val (sampleIndex, _) = tx.inputSignatures.sampleWithIndex()
      val signaturesNew    = tx.inputSignatures.replace(sampleIndex, Signature.generate)
      tx.copy(inputSignatures = signaturesNew).fail(InvalidSignature)
      tx.copy(inputSignatures = tx.inputSignatures ++ tx.inputSignatures)
        .fail(TooManyInputSignatures)
      tx.copy(inputSignatures = tx.inputSignatures.init).fail(NotEnoughSignature)
    }
  }

  it should "compress signatures" in new Fixture {
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

    implicit val validator = checkWitnesses(_: Transaction, preOutputs)

    tx1.unsigned.inputs.length is 2
    tx1.inputSignatures.length is 1
    tx1.pass()

    val tx2 = tx1.copy(inputSignatures = tx1.inputSignatures ++ tx1.inputSignatures)
    tx2.unsigned.inputs.length is 2
    tx2.inputSignatures.length is 2
    tx2.fail(TooManyInputSignatures)
  }

  behavior of "lockup script"

  it should "validate p2pkh" in new Fixture {
    implicit val validator = validateTxOnlyForTest(_, blockFlow)

    forAll(keypairGen) { case (priKey, pubKey) =>
      val lockup   = LockupScript.p2pkh(pubKey)
      val unlock   = UnlockScript.p2pkh(pubKey)
      val unsigned = prepareOutput(lockup, unlock)
      val tx       = Transaction.from(unsigned, priKey)

      tx.pass()
      tx.replaceUnlock(UnlockScript.p2pkh(PublicKey.generate)).fail(InvalidPublicKeyHash)
      tx.copy(inputSignatures = AVector(Signature.generate)).fail(InvalidSignature)
    }
  }

  it should "invalidate p2mpkh" in new Fixture {
    val (priKey0, pubKey0) = keypairGen.sample.value
    val (priKey1, pubKey1) = keypairGen.sample.value
    val (_, pubKey2)       = keypairGen.sample.value

    def tx(keys: (PublicKey, Int)*): Transaction = {
      val lockup   = LockupScript.p2mpkhUnsafe(AVector(pubKey0, pubKey1, pubKey2), 2)
      val unlock   = UnlockScript.p2mpkh(AVector.from(keys))
      val unsigned = prepareOutput(lockup, unlock)
      sign(unsigned, priKey0, priKey1)
    }

    implicit val validator = validateTxOnlyForTest(_, blockFlow)

    tx(pubKey0 -> 0).fail(InvalidNumberOfPublicKey)
    tx(pubKey0 -> 0, pubKey1 -> 1, pubKey2 -> 2).fail(InvalidNumberOfPublicKey)
    tx(pubKey1 -> 1, pubKey0 -> 0).fail(InvalidP2mpkhUnlockScript)
    tx(pubKey1 -> 0, pubKey0 -> 0).fail(InvalidP2mpkhUnlockScript)
    tx(pubKey1 -> 1, pubKey0 -> 1).fail(InvalidP2mpkhUnlockScript)
    tx(pubKey0 -> 0, pubKey1 -> 3).fail(InvalidP2mpkhUnlockScript)
    tx(pubKey0 -> 0, pubKey1 -> 2).fail(InvalidPublicKeyHash)
    tx(pubKey1 -> 0, pubKey1 -> 1).fail(InvalidPublicKeyHash)
    tx(pubKey0 -> 0, pubKey2 -> 2).fail(InvalidSignature)
    tx(pubKey0 -> 0, pubKey1 -> 1).pass()
  }

  it should "validate p2sh" in new Fixture {
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

    implicit val validator = validateTxOnlyForTest(_, blockFlow)

    val tx0 = Transaction.from(unsigned, AVector.empty[Signature])
    tx0.pass()

    val tx1 = tx0.replaceUnlock(UnlockScript.p2sh(script, AVector(Val.U256(50))))
    tx1.fail(UnlockScriptExeFailed(AssertionFailed))

    val newScript = Compiler.compileAssetScript(rawScript(50)).rightValue
    val tx2       = tx0.replaceUnlock(UnlockScript.p2sh(newScript, AVector(Val.U256(50))))
    tx2.fail(InvalidScriptHash)
  }

  trait GasFixture extends Fixture {
    def groupIndex: GroupIndex
    def tx: Transaction
    lazy val initialGas = minimalGas
    lazy val blockEnv =
      BlockEnv(NetworkId.AlephiumMainNet, TimeStamp.now(), consensusConfig.maxMiningTarget, None)
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
    val unlock   = UnlockScript.p2sh(script, AVector.empty)
    val unsigned = prepareOutput(lockup, unlock)
    val tx       = Transaction.from(unsigned, AVector.empty[Signature])

    val groupIndex = lockup.groupIndex
    val gasRemaining =
      checkUnlockScript(blockEnv, txEnv, initialGas, script.hash, script, AVector.empty).rightValue

    initialGas is gasRemaining.addUnsafe(
      script.bytes.size +
        GasHash.gas(script.bytes.size).value +
        GasSchedule.callGas.value
    )
  }

  trait ScriptFixture extends GasFixture {
    def rawScript: String

    lazy val script     = Compiler.compileTxScript(rawScript).rightValue
    lazy val chainIndex = ChainIndex.unsafe(0, 0)
    lazy val block      = simpleScript(blockFlow, chainIndex, script)
    lazy val tx         = block.nonCoinbase.head
    lazy val groupIndex = GroupIndex.unsafe(0)
    lazy val worldState = blockFlow.getBestCachedWorldState(groupIndex).rightValue
  }

  it should "charge gas for tx script size" in new ScriptFixture {
    val rawScript =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript P2sh {
         |  return
         |}
         |""".stripMargin

    val gasRemaining =
      checkTxScript(chainIndex, tx, initialGas, worldState, prevOutputs, blockEnv).rightValue
    initialGas is gasRemaining.addUnsafe(script.bytes.size + GasSchedule.callGas.value)

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

  it should "match generated contract inputs and outputs" in new ScriptFixture {
    val rawScript =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  return
         |}
         |""".stripMargin

    implicit val validator = (tx: Transaction) => {
      checkTxScript(chainIndex, tx, initialGas, worldState, prevOutputs, blockEnv)
    }

    tx.generatedOutputs.length is 0
    tx.contractInputs.length is 0
    tx.pass()

    val contractId = Hash.generate
    val output = contractOutputGen(scriptGen = Gen.const(LockupScript.P2C(contractId))).sample.get
    val outputRef = ContractOutputRef.unsafe(output.hint, contractId)
    tx.copy(contractInputs = AVector(outputRef)).fail(InvalidContractInputs)

    tx.copy(generatedOutputs = AVector(assetOutputGen.sample.get)).fail(InvalidGeneratedOutputs)
  }

  it should "check script execution flag, intra group" in new ScriptFixture {

    info("valid script")
    val rawScript =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  return
         |}
         |""".stripMargin

    implicit val validator = (tx: Transaction) => {
      checkTxScript(chainIndex, tx, initialGas, worldState, prevOutputs, blockEnv)
    }

    tx.scriptExecutionOk is true
    tx.pass()

    tx.copy(scriptExecutionOk = false).fail(InvalidScriptExecutionFlag)

    info("script that fails execution")

    // scalastyle:off no.equal
    val invalidExecutionRawScript =
      s"""
         |TxScript Main {
         |  assert!(1 == 2)
         |}
         |""".stripMargin
    // scalastyle:on no.equal

    val invalidExecutionScript = Compiler.compileTxScript(invalidExecutionRawScript).rightValue
    val invalidExecutionTx     = tx.updateUnsigned(_.copy(scriptOpt = Some(invalidExecutionScript)))
    invalidExecutionTx.fail(InvalidScriptExecutionFlag)

    invalidExecutionTx.copy(scriptExecutionOk = false).pass()
  }

  it should "check script execution flag, inter group" in new ScriptFixture {
    val rawScript =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  return
         |}
         |""".stripMargin

    implicit val validator = (tx: Transaction) => {
      checkTxScript(ChainIndex.unsafe(0, 1), tx, initialGas, worldState, prevOutputs, blockEnv)
    }

    tx.scriptExecutionOk is true
    tx.fail(UnexpectedTxScript)

    val noScriptTx = tx.updateUnsigned(_.copy(scriptOpt = None))
    noScriptTx.pass()
    noScriptTx.copy(scriptExecutionOk = false).fail(InvalidScriptExecutionFlag)
  }

  it should "validate mempool tx fully" in new FlowFixture {
    val txValidator = TxValidation.build
    txValidator
      .validateMempoolTxTemplate(outOfGasTxTemplate, blockFlow)
      .leftValue isE TxScriptExeFailed(OutOfGas)
  }
}
