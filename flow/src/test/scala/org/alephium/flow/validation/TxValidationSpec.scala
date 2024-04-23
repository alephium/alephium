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
import org.alephium.ralph.Compiler
import org.alephium.util.{AVector, Duration, TimeStamp, U256}

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
        _ <- checkStateless(
          chainIndex,
          tx,
          checkDoubleSpending = true,
          HardFork.Leman,
          isCoinbase = false
        )
        _ <- checkStateful(
          chainIndex,
          tx,
          cachedWorldState,
          preOutputs.map(_.referredOutput),
          None,
          BlockEnv(chainIndex, networkConfig.networkId, headerTs, Target.Max, None)
        )
      } yield ()
    }

    def checkWitnesses(
        tx: Transaction,
        preOutputs: AVector[TxOutput]
    ): TxValidationResult[GasBox] = {
      checkWitnesses(tx, preOutputs, TimeStamp.now())
    }

    def checkWitnesses(
        tx: Transaction,
        preOutputs: AVector[TxOutput],
        timestamp: TimeStamp
    ): TxValidationResult[GasBox] = {
      val blockEnv =
        BlockEnv(tx.chainIndex, networkConfig.networkId, timestamp, Target.Max, None)
      checkGasAndWitnesses(tx, preOutputs, blockEnv)
    }

    def prepareOutputs(lockup: LockupScript.Asset, unlock: UnlockScript, outputsNum: Int) = {
      val group                 = lockup.groupIndex
      val (genesisPriKey, _, _) = genesisKeys(group.value)
      val outputs =
        AVector.fill(outputsNum + 1)(TxOutputInfo(lockup, ALPH.alph(1), AVector.empty, None))
      val unsignedTx = blockFlow
        .transfer(
          genesisPriKey.publicKey,
          outputs,
          None,
          nonCoinbaseMinGasPrice,
          defaultUtxoLimit
        )
        .rightValue
        .rightValue
      val tx         = Transaction.from(unsignedTx, genesisPriKey)
      val chainIndex = tx.chainIndex
      val block      = mineWithTxs(blockFlow, chainIndex)((_, _) => AVector(tx))
      addAndCheck(blockFlow, block)

      blockFlow
        .transfer(
          None,
          lockup,
          unlock,
          outputs.tail,
          None,
          nonCoinbaseMinGasPrice,
          defaultUtxoLimit
        )
        .rightValue
        .rightValue
    }

    def prepareOutput(lockup: LockupScript.Asset, unlock: UnlockScript) = {
      prepareOutputs(lockup, unlock, 1)
    }

    def sign(unsigned: UnsignedTransaction, privateKeys: PrivateKey*): Transaction = {
      val signatures = privateKeys.map(SignatureSchema.sign(unsigned.id, _))
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
          _      <- validateTxOnlyForTest(transaction, blockFlow, None)
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
        updateRandomFixedOutputsWithoutToken(output => output.copy(amount = f(output.amount)))
      }

      def zeroTokenAmount(): Transaction = {
        updateRandomFixedOutputs(_.copy(tokens = AVector(TokenId.generate -> U256.Zero)))
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
          output.tokens.view.filter(_._1 equals tokenId).map(_._2).fold(acc)(_ + _)
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

      def updateRandomFixedOutputsWithoutToken(f: AssetOutput => AssetOutput): Transaction = {
        val (output, index) = Random
          .shuffle(tx.unsigned.fixedOutputs.view.zipWithIndex.filter(p => p._1.tokens.isEmpty))
          .head
        val outputNew = f(output)
        tx.updateUnsigned(_.copy(fixedOutputs = tx.unsigned.fixedOutputs.replace(index, outputNew)))
      }

      def updateRandomGeneratedOutputs(f: TxOutput => TxOutput): Transaction = {
        val (index, output) = tx.generatedOutputs.sampleWithIndex()
        val outputNew       = f(output)
        tx.copy(generatedOutputs = tx.generatedOutputs.replace(index, outputNew))
      }

      def updateRandomInputs(f: TxInput => TxInput): Transaction = {
        val (index, _) = tx.unsigned.inputs.sampleWithIndex()
        updateInput(index, f)
      }

      def updateInput(index: Int, f: TxInput => TxInput): Transaction = {
        val input    = tx.unsigned.inputs(index)
        val inputNew = f(input)
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

      def addNewTokenOutput(): Transaction = {
        val firstOutput = tx.unsigned.fixedOutputs.head
        val newTokenOutput =
          firstOutput.copy(amount = dustUtxoAmount, tokens = AVector(TokenId.generate -> 1))
        tx.copy(generatedOutputs = tx.generatedOutputs :+ newTokenOutput)
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

    forAll(transactionGenWithCompressedUnlockScripts()) { case (tx, preOutputs) =>
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

    val modified0 = tx.inputs(AVector.fill(ALPH.MaxTxInputNum)(input))
    val modified1 = tx.inputs(AVector.fill(ALPH.MaxTxInputNum + 1)(input))
    val contractOutputRef =
      ContractOutputRef.unsafe(Hint.unsafe(1), TxOutputRef.unsafeKey(Hash.zero))
    val modified2 = tx.copy(contractInputs = AVector(contractOutputRef))
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
    forAll(transactionGenWithPreOutputs(2, 1)) { case (tx, preOutputs) =>
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

  it should "check gas bounds deprecated" in new Fixture {
    val (isCoinbase, hardfork) =
      AVector(true -> HardFork.Mainnet, true -> HardFork.Leman, false -> HardFork.Mainnet).sample()

    implicit val validator = checkGasBound(_, isCoinbase, hardfork)

    val tx = transactionGen(2, 1).sample.value
    tx.pass()

    tx.gasAmount(GasBox.unsafeTest(-1)).fail(InvalidStartGas)
    tx.gasAmount(GasBox.unsafeTest(0)).fail(InvalidStartGas)
    tx.gasAmount(minimalGas).pass()
    tx.gasAmount(minimalGas.use(1).rightValue).fail(InvalidStartGas)
    tx.gasAmount(maximalGasPerTx).pass()
    tx.gasAmount(maximalGasPerTx.addUnsafe(1)).fail(InvalidStartGas)

    tx.gasPrice(GasPrice(0)).fail(InvalidGasPrice)
    tx.gasPrice(coinbaseGasPrice).pass()
    tx.gasPrice(GasPrice(coinbaseGasPrice.value - 1)).fail(InvalidGasPrice)
    tx.gasPrice(GasPrice(ALPH.MaxALPHValue - 1)).pass()
    tx.gasPrice(GasPrice(ALPH.MaxALPHValue)).fail(InvalidGasPrice)
  }

  it should "check gas bounds for non-coinbase" in new Fixture {
    implicit val validator = checkGasBound(_, isCoinbase = false, HardFork.Leman)

    val tx = transactionGen(2, 1).sample.value
    tx.pass()

    tx.gasAmount(GasBox.unsafeTest(-1)).fail(InvalidStartGas)
    tx.gasAmount(GasBox.unsafeTest(0)).fail(InvalidStartGas)
    tx.gasAmount(minimalGas).pass()
    tx.gasAmount(minimalGas.use(1).rightValue).fail(InvalidStartGas)
    tx.gasAmount(maximalGasPerTx).pass()
    tx.gasAmount(maximalGasPerTx.addUnsafe(1)).fail(InvalidStartGas)

    tx.gasPrice(GasPrice(0)).fail(InvalidGasPrice)
    tx.gasPrice(coinbaseGasPrice).fail(InvalidGasPrice)
    tx.gasPrice(nonCoinbaseMinGasPrice).pass()
    tx.gasPrice(GasPrice(nonCoinbaseMinGasPrice.value - 1)).fail(InvalidGasPrice)
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

  it should "check the number of tokens for asset outputs" in new Fixture {
    {
      info("Leman hardfork")
      implicit val validator = checkOutputStats(_, HardFork.Leman)

      forAll(transactionGen(numTokensGen = maxTokenPerAssetUtxo + 1))(_.fail(InvalidOutputStats))
      forAll(transactionGen(numTokensGen = maxTokenPerAssetUtxo))(_.pass())
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

  it should "check the number of tokens for contract outputs" in new Fixture {
    val tx = transactionGen().sample.get

    def update(tokenPerContract: Int): Transaction = {
      val contractOutput =
        contractOutputGen(
          _tokensGen = tokensGen(1, tokenPerContract),
          scriptGen = LockupScript.P2C(ContractId.zero)
        ).sample.get
      tx.copy(generatedOutputs = AVector(contractOutput))
    }

    {
      info("Leman hardfork")
      implicit val validator = checkOutputStats(_, HardFork.Leman)

      update(maxTokenPerContractUtxo + 1).fail(InvalidOutputStats)
      update(maxTokenPerContractUtxo).pass()
    }

    {
      info("Pre-Leman hardfork")
      implicit val validator = checkOutputStats(_, HardFork.Mainnet)

      update(deprecatedMaxTokenPerUtxo + 1).fail(InvalidOutputStats)
      update(maxTokenPerContractUtxo + 1).pass()
      update(deprecatedMaxTokenPerUtxo).pass()
    }
  }

  it should "check the number of public keys in p2mpk for leman fork" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      implicit val validator = nestedValidator(checkOutputStats(_, HardFork.Leman), preOutputs)

      val chainIndex = tx.chainIndex
      val p2pkh      = p2pkhLockupGen(chainIndex.from).sample.get
      val invalidP2MPK = LockupScript.P2MPKH
        .unsafe(AVector(p2pkh.pkHash) ++ AVector.fill(ALPH.MaxKeysInP2MPK)(Hash.generate), 1)

      val updateFixedOutput = Random.nextInt(tx.outputsLength) < tx.unsigned.fixedOutputs.length
      val txNew = if (updateFixedOutput) {
        tx.updateRandomFixedOutputs(_.copy(lockupScript = invalidP2MPK))
      } else {
        tx.updateRandomGeneratedOutputs {
          case o: AssetOutput    => o.copy(lockupScript = invalidP2MPK)
          case o: ContractOutput => o
        }
      }
      txNew.fail(TooManyKeysInMultisig)
    }

    val keys       = AVector.fill(ALPH.MaxKeysInP2MPK)(PublicKey.generate)
    val validP2MPK = LockupScript.p2mpkh(keys, 1).value
    val tx =
      transactionGen().sample.get.updateRandomFixedOutputs(_.copy(lockupScript = validP2MPK))
    checkOutputStats(tx, HardFork.Leman).isRight is true
  }

  it should "check the number of public keys in p2mpk for Mainnet fork" in new Fixture {
    val tooManyKeys = AVector.fill(ALPH.MaxKeysInP2MPK + 1)(PublicKey.generate)
    val p2mpkh0     = LockupScript.p2mpkh(tooManyKeys.init, 1).value
    val p2mpkh1     = LockupScript.p2mpkh(tooManyKeys, ALPH.MaxKeysInP2MPK + 1).value
    val tx0 =
      transactionGen().sample.get.updateRandomFixedOutputs(_.copy(lockupScript = p2mpkh0))
    val tx1 =
      transactionGen().sample.get.updateRandomFixedOutputs(_.copy(lockupScript = p2mpkh1))
    checkOutputStats(tx0, HardFork.Mainnet).isRight is true
    checkOutputStats(tx1, HardFork.Mainnet).isRight is true
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
            val outputRefNew = AssetOutputRef.from(scriptHint, input.outputRef.key)
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
    val oversizedData  = ByteString.fromArrayUnsafe(Array.fill(ALPH.MaxOutputDataSize + 1)(0))
    val justEnoughData = ByteString.fromArrayUnsafe(Array.fill(ALPH.MaxOutputDataSize)(0))
    oversizedData.length is ALPH.MaxOutputDataSize + 1
    justEnoughData.length is ALPH.MaxOutputDataSize

    forAll(transactionGenWithPreOutputs(), Gen.oneOf(HardFork.Mainnet, HardFork.Leman)) {
      case ((tx, preOutputs), hardFork) =>
        implicit val validator = nestedValidator(checkOutputStats(_, hardFork), preOutputs)

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

    val tx =
      transactionGen().sample.get.updateRandomFixedOutputs(_.copy(additionalData = justEnoughData))
    HardFork.All.foreach { hf => checkOutputStats(tx, hf).isRight is true }
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
    forAll(transactionGenWithPreOutputs(tokensNumGen = 1)) { case (tx, preOutputs) =>
      implicit val validator = nestedValidator(
        checkTokenBalance(_, preOutputs.map(_.referredOutput)),
        preOutputs
      )

      val tokenId     = tx.sampleToken()
      val tokenAmount = tx.getTokenAmount(tokenId)
      whenever(tx.unsigned.fixedOutputs.view.count(_.tokens.exists(_._1 == tokenId)) >= 2) { // only able to overflow 2 outputs
        tx.modifyTokenAmount(tokenId, U256.MaxValue - tokenAmount + 1 + _).fail(BalanceOverFlow)
      }
    }
  }

  trait TokenBalanceFixture extends Fixture {
    def updateTx(tx: Transaction): Transaction

    def method(useAssets: Boolean) = Method[StatefulContext](
      isPublic = true,
      usePreapprovedAssets = useAssets,
      useContractAssets = useAssets,
      usePayToContractOnly = false,
      argsLength = 0,
      localsLength = 0,
      returnLength = 0,
      instrs = AVector.empty
    )
    val payableScript    = StatefulScript.unsafe(AVector(method(true)))
    val nonPayableScript = StatefulScript.unsafe(AVector(method(false)))

    def test(pass: Boolean = false) = {
      forAll(
        transactionGenWithPreOutputs(
          tokensNumGen = Gen.choose(1, 10),
          chainIndexGen = ChainIndex.unsafe(0, 0)
        )
      ) { case ((tx, preOutputs)) =>
        implicit val validator = nestedValidator(
          checkTokenBalance(_, preOutputs.map(_.referredOutput)),
          preOutputs
        )

        val newTx = updateTx(tx)
        if (pass) {
          val result = validator(newTx)
          (result.isRight || result.leftValue.rightValue != InvalidTokenBalance) is true
        } else {
          newTx.fail(InvalidTokenBalance)
        }
      }
    }
  }

  it should "validate token balances for normal tx with invalid token amount" in new TokenBalanceFixture {
    def updateTx(tx: Transaction): Transaction = {
      val tokenId = tx.sampleToken()
      val result  = tx.modifyTokenAmount(tokenId, _ + 1)
      result.unsigned.scriptOpt is None
      result.scriptExecutionOk is true
      result
    }

    test()
  }

  it should "validate token balances for non-payable script tx with invalid token amount" in new TokenBalanceFixture {
    def updateTx(tx: Transaction): Transaction = {
      val tokenId     = tx.sampleToken()
      val executionOk = Random.nextBoolean()
      val result = tx
        .updateUnsigned(_.copy(scriptOpt = Some(nonPayableScript)))
        .modifyTokenAmount(tokenId, _ + 1)
        .copy(scriptExecutionOk = executionOk)
      result.isEntryMethodPayable is false
      result.scriptExecutionOk is executionOk
      result
    }

    test()
  }

  it should "validate token balances for non-payable script tx with invalid new token" in new TokenBalanceFixture {
    def updateTx(tx: Transaction): Transaction = {
      val executionOk = Random.nextBoolean()
      val result = tx
        .updateUnsigned(_.copy(scriptOpt = Some(nonPayableScript)))
        .addNewTokenOutput()
        .copy(scriptExecutionOk = executionOk)
      result.isEntryMethodPayable is false
      result.scriptExecutionOk is executionOk
      result
    }

    test()
  }

  it should "validate token balances for payable script tx with invalid token amount" in new TokenBalanceFixture {
    def updateTx(tx: Transaction): Transaction = {
      val tokenId     = tx.sampleToken()
      val executionOk = Random.nextBoolean()
      val result = tx
        .updateUnsigned(_.copy(scriptOpt = Some(payableScript)))
        .modifyTokenAmount(tokenId, _ + 1)
        .copy(scriptExecutionOk = executionOk)
      result.isEntryMethodPayable is true
      result.scriptExecutionOk is executionOk
      result
    }

    test()
  }

  it should "validate token balances for script tx with invalid new token: execution failed" in new TokenBalanceFixture {
    def updateTx(tx: Transaction): Transaction = {
      val result = tx
        .updateUnsigned(_.copy(scriptOpt = Some(payableScript)))
        .addNewTokenOutput()
        .copy(scriptExecutionOk = false)
      result.isEntryMethodPayable is true
      result.scriptExecutionOk is false
      result
    }

    test()
  }

  // This would pass `checkTokenBalance`, but VM execution would fail
  it should "validate token balances for script tx with invalid new token: execution succeeded" in new TokenBalanceFixture {
    def updateTx(tx: Transaction): Transaction = {
      val result = tx
        .updateUnsigned(_.copy(scriptOpt = Some(payableScript)))
        .addNewTokenOutput()
      result.isEntryMethodPayable is true
      result.scriptExecutionOk is true
      result
    }

    test(pass = true)
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
            usePayToContractOnly = false,
            argsLength = 0,
            localsLength = 0,
            returnLength = 0,
            instrs = AVector.empty
          )
          val script = StatefulScript.unsafe(AVector(method))

          invalidTx.updateUnsigned(_.copy(scriptOpt = Some(script)))
        }

        invalidTxWithScript.fail(InvalidTokenBalance)
    }
  }

  it should "check the exact gas cost" in new Fixture {
    import GasSchedule._

    val chainIndex  = chainIndexGenForBroker(brokerConfig).sample.get
    val block       = transfer(blockFlow, chainIndex)
    val tx          = block.nonCoinbase.head
    val blockEnv    = BlockEnv.from(chainIndex, block.header)
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
    forAll(transactionGenWithPreOutputs(2, 1)) { case (tx, preOutputs) =>
      val inputsState              = preOutputs.map(_.referredOutput).as[TxOutput]
      val ScriptPair(_, unlock, _) = p2pkScriptGen(GroupIndex.unsafe(1)).sample.get

      implicit val validator = nestedValidator(checkWitnesses(_, inputsState), preOutputs)

      tx.updateInput(0, _.copy(unlockScript = unlock)).fail(InvalidPublicKeyHash)

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
    val preOutputs = blockFlow
      .getBestPersistedWorldState(chainIndex.from)
      .rightValue
      .getPreOutputs(tx1)
      .rightValue

    val preLemanTimeStamp =
      networkConfig.lemanHardForkTimestamp.minusUnsafe(Duration.ofSecondsUnsafe(1))
    val validatorPreLeman = checkWitnesses(_: Transaction, preOutputs, preLemanTimeStamp)
    val validatorLeman    = checkWitnesses(_: Transaction, preOutputs)

    tx1.unsigned.inputs.length is 2
    tx1.inputSignatures.length is 1
    tx1.pass()(validatorPreLeman)
    tx1.pass()(validatorLeman)

    val tx2 = tx1.copy(inputSignatures = tx1.inputSignatures ++ tx1.inputSignatures)
    tx2.unsigned.inputs.length is 2
    tx2.inputSignatures.length is 2
    tx2.fail(TooManyInputSignatures)(validatorPreLeman)
    tx2.fail(TooManyInputSignatures)(validatorLeman)
  }

  trait CompressUnlockScriptsFixture extends Fixture {
    def lockup: LockupScript.Asset
    def unlock: UnlockScript

    def toSignedTx(unsignedTx: UnsignedTransaction): Transaction

    val preLemanValidator = validateTxOnlyForTest(_, blockFlow, Some(HardFork.Mainnet))
    val lemanValidator    = validateTxOnlyForTest(_, blockFlow, None)

    def validate() = {
      val unsignedTx0 = prepareOutputs(lockup, unlock, 2)
      unsignedTx0.inputs.length is 3
      unsignedTx0.inputs.foreach(_.unlockScript is unlock)
      val tx0 = toSignedTx(unsignedTx0)
      tx0.pass()(lemanValidator)
      tx0.pass()(preLemanValidator)

      val newInputs = unsignedTx0.inputs.head +: unsignedTx0.inputs.tail.map(
        _.copy(unlockScript = UnlockScript.SameAsPrevious)
      )
      val unsignedTx1 = unsignedTx0.copy(inputs = newInputs)
      val tx1         = toSignedTx(unsignedTx1)
      tx1.pass()(lemanValidator)
      tx1.fail(InvalidUnlockScriptType)(preLemanValidator)
    }
  }

  it should "compress p2pkh unlock scripts" in new CompressUnlockScriptsFixture {
    val (priKey, pubKey) = keypairGen.sample.get
    val lockup           = LockupScript.p2pkh(pubKey)
    val unlock           = UnlockScript.p2pkh(pubKey)

    def toSignedTx(unsignedTx: UnsignedTransaction): Transaction = {
      Transaction.from(unsignedTx, priKey)
    }

    validate()
  }

  it should "compress p2mpkh unlock scripts" in new CompressUnlockScriptsFixture {
    val (priKey0, pubKey0) = keypairGen.sample.value
    val (priKey1, pubKey1) = keypairGen.sample.value
    val (_, pubKey2)       = keypairGen.sample.value
    val lockup             = LockupScript.p2mpkhUnsafe(AVector(pubKey0, pubKey1, pubKey2), 2)
    val unlock             = UnlockScript.p2mpkh(AVector.from(Seq(pubKey0 -> 0, pubKey1 -> 1)))

    def toSignedTx(unsignedTx: UnsignedTransaction): Transaction = {
      sign(unsignedTx, priKey0, priKey1)
    }

    validate()
  }

  it should "compress p2sh unlock scripts" in new CompressUnlockScriptsFixture {
    val assetScript =
      s"""
         |AssetScript P2sh {
         |  pub fn main() -> () {}
         |}
         |""".stripMargin

    val script = Compiler.compileAssetScript(assetScript).rightValue._1
    val lockup = LockupScript.p2sh(script)
    val unlock = UnlockScript.p2sh(script, AVector.empty)

    def toSignedTx(unsignedTx: UnsignedTransaction): Transaction = {
      Transaction.from(unsignedTx, AVector.empty[Signature])
    }

    validate()
  }

  it should "check if it is the same unlock script as previous" in new Fixture {
    def unlockScriptGen(): Gen[UnlockScript] = {
      groupIndexGen.flatMap { groupIndex =>
        Gen.oneOf(p2pkhUnlockGen(groupIndex), p2mpkhUnlockGen(3, 2, groupIndex))
      }
    }

    forAll(unlockScriptGen()) { unlockScript =>
      sameUnlockScriptAsPrevious(unlockScript, unlockScript, HardFork.Mainnet) is true
      sameUnlockScriptAsPrevious(unlockScript, unlockScript, HardFork.Leman) is true
      sameUnlockScriptAsPrevious(
        UnlockScript.SameAsPrevious,
        unlockScript,
        HardFork.Mainnet
      ) is false
      sameUnlockScriptAsPrevious(UnlockScript.SameAsPrevious, unlockScript, HardFork.Leman) is true
    }

    sameUnlockScriptAsPrevious(
      UnlockScript.SameAsPrevious,
      UnlockScript.SameAsPrevious,
      HardFork.Leman
    ) is true
    sameUnlockScriptAsPrevious(
      UnlockScript.SameAsPrevious,
      UnlockScript.SameAsPrevious,
      HardFork.Mainnet
    ) is true
  }

  behavior of "lockup script"

  it should "validate p2pkh" in new Fixture {
    implicit val validator = validateTxOnlyForTest(_, blockFlow, None)

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

    implicit val validator = validateTxOnlyForTest(_, blockFlow, None)

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
         |    assert!(a == $n, 0)
         |  }
         |}
         |""".stripMargin
    // scalastyle:on no.equal

    val script   = Compiler.compileAssetScript(rawScript(51)).rightValue._1
    val lockup   = LockupScript.p2sh(script)
    val unlock   = UnlockScript.p2sh(script, AVector(Val.U256(51)))
    val unsigned = prepareOutput(lockup, unlock)

    implicit val validator = validateTxOnlyForTest(_, blockFlow, None)

    val tx0 = Transaction.from(unsigned, AVector.empty[Signature])
    tx0.pass()

    val tx1 = tx0.replaceUnlock(UnlockScript.p2sh(script, AVector(Val.U256(50))))
    tx1.fail(UnlockScriptExeFailed(AssertionFailedWithErrorCode(None, 0)))

    val newScript = Compiler.compileAssetScript(rawScript(50)).rightValue._1
    val tx2       = tx0.replaceUnlock(UnlockScript.p2sh(newScript, AVector(Val.U256(50))))
    tx2.fail(InvalidScriptHash)
  }

  trait GasFixture extends Fixture {
    def groupIndex: GroupIndex
    def tx: Transaction
    lazy val initialGas = minimalGas
    lazy val blockEnv = {
      val now = TimeStamp.now()
      BlockEnv(
        tx.chainIndex,
        NetworkId.AlephiumMainNet,
        now,
        consensusConfigs.getConsensusConfig(now).maxMiningTarget,
        None
      )
    }
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

    val script   = Compiler.compileAssetScript(rawScript).rightValue._1
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

    val randomOutputRef = ContractId.random.inaccurateFirstOutputRef()
    tx.copy(contractInputs = AVector(randomOutputRef)).fail(InvalidContractInputs)

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
         |  assert!(1 == 2, 0)
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
