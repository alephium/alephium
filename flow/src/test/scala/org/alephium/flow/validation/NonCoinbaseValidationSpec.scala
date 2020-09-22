package org.alephium.flow.validation

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.io.IOResult
import org.alephium.protocol.{ALF, Hash, Signature}
import org.alephium.protocol.model._
import org.alephium.protocol.model.ModelGenerators.{AssetInputInfo, ContractInfo, TxInputStateInfo}
import org.alephium.protocol.vm.{LockupScript, VMFactory, WorldState}
import org.alephium.util.{AVector, Random, U64}

class NonCoinbaseValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  def passCheck[T](result: TxValidationResult[T]): Assertion = {
    result.isRight is true
  }

  def failCheck[T](result: TxValidationResult[T], error: InvalidTxStatus): Assertion = {
    result.left.value isE error
  }

  def passValidation(result: IOResult[TxStatus]): Assertion = {
    result.toOption.get is ValidTx
  }

  def failValidation(result: IOResult[TxStatus], error: InvalidTxStatus): Assertion = {
    result.toOption.get is error
  }

  class Fixture extends NonCoinbaseValidation.Impl with VMFactory {
    // TODO: prepare blockflow to test checkMempool
    def prepareWorldState(inputInfos: AVector[TxInputStateInfo]): WorldState = {
      inputInfos.fold(cachedWorldState) {
        case (worldState, inputInfo: AssetInputInfo) =>
          worldState
            .addAsset(inputInfo.txInput.outputRef.asInstanceOf[AssetOutputRef],
                      inputInfo.referredOutput)
            .toOption
            .get
        case (worldState, inputInfo: ContractInfo) =>
          worldState
            .addContract(inputInfo.txInput.outputRef.asInstanceOf[ContractOutputRef],
                         inputInfo.referredOutput,
                         inputInfo.state)
            .toOption
            .get
      }
    }
  }

  it should "pass valid transactions" in new Fixture {
    forAll(transactionGenWithPreOutputs(1, 1, chainIndexGen = chainIndexGenForBroker(brokerConfig))) {
      case (tx, preOutputs) =>
        passCheck(checkBlockTx(tx, prepareWorldState(preOutputs)))
    }
  }

  behavior of "Stateless Validation"

  trait StatelessFixture extends Fixture {
    val blockFlow = genesisBlockFlow()

    def modifyAlfAmount(tx: Transaction, delta: U64): Transaction = {
      val (index, output) = tx.unsigned.fixedOutputs.sampleWithIndex()
      val outputNew = output match {
        case o: AssetOutput    => o.copy(amount = o.amount + delta)
        case o: ContractOutput => o.copy(amount = o.amount + delta)
      }
      tx.copy(
        unsigned =
          tx.unsigned.copy(fixedOutputs = tx.unsigned.fixedOutputs.replace(index, outputNew)))
    }
  }

  it should "check empty inputs" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs(1, 1)) {
      case (tx, preOutputs) =>
        val unsignedNew = tx.unsigned.copy(inputs = AVector.empty)
        val txNew       = tx.copy(unsigned        = unsignedNew)
        failCheck(checkInputNum(txNew), NoInputs)
        failValidation(validateMempoolTx(txNew, blockFlow), NoInputs)
        failCheck(checkBlockTx(txNew, prepareWorldState(preOutputs)), NoInputs)
    }
  }

  it should "check empty outputs" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs(1, 1)) {
      case (tx, preOutputs) =>
        val unsignedNew = tx.unsigned.copy(fixedOutputs = AVector.empty)
        val txNew       = tx.copy(unsigned              = unsignedNew)
        failCheck(checkOutputNum(txNew), NoOutputs)
        failValidation(validateMempoolTx(txNew, blockFlow), NoOutputs)
        failCheck(checkBlockTx(txNew, prepareWorldState(preOutputs)), NoOutputs)
    }
  }

  it should "check ALF balance overflow" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs()) {
      case (tx, preOutputs) =>
        whenever(tx.unsigned.fixedOutputs.length >= 2) { // only able to overflow 2 outputs
          val alfAmount = tx.alfAmountInOutputs.get
          val delta     = U64.MaxValue - alfAmount + 1
          val txNew     = modifyAlfAmount(tx, delta)
          failCheck(checkAlfOutputAmount(txNew), BalanceOverFlow)
          failValidation(validateMempoolTx(txNew, blockFlow), BalanceOverFlow)
          failCheck(checkBlockTx(txNew, prepareWorldState(preOutputs)), BalanceOverFlow)
        }
    }
  }

  it should "check the inputs indexes" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs(2, 5)) {
      case (tx, preOutputs) =>
        val chainIndex = tx.chainIndex
        val inputs     = tx.unsigned.inputs
        val localUnsignedGen =
          for {
            fromGroupNew <- groupIndexGen.retryUntil(!chainIndex.relateTo(_))
            scriptHint   <- scriptHintGen(fromGroupNew)
            selected     <- Gen.choose(0, inputs.length - 1)
          } yield {
            val input = inputs(selected)
            val outputRefNew = input.outputRef match {
              case ref: AssetOutputRef    => AssetOutputRef.from(scriptHint, ref.key)
              case ref: ContractOutputRef => ContractOutputRef.from(scriptHint, ref.key)
            }
            val inputsNew = inputs.replace(selected, input.copy(outputRef = outputRefNew))
            tx.unsigned.copy(inputs = inputsNew)
          }
        forAll(localUnsignedGen) { unsignedNew =>
          val txNew = tx.copy(unsigned = unsignedNew)
          failCheck(checkChainIndex(txNew), InvalidInputGroupIndex)
          failValidation(validateMempoolTx(txNew, blockFlow), InvalidInputGroupIndex)
          failCheck(checkBlockTx(txNew, prepareWorldState(preOutputs)), InvalidInputGroupIndex)
        }
    }
  }

  it should "check the output indexes" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs(2, 5)) {
      case (tx, preOutputs) =>
        val chainIndex = tx.chainIndex
        val outputs    = tx.unsigned.fixedOutputs
        whenever(
          !chainIndex.isIntraGroup && outputs.filter(_.toGroup equals chainIndex.to).length >= 2) {
          val localUnsignedGen =
            for {
              toGroupNew      <- groupIndexGen.retryUntil(!chainIndex.relateTo(_))
              lockupScriptNew <- p2pkhLockupGen(toGroupNew)
              selected        <- Gen.choose(0, outputs.length - 1)
            } yield {
              val outputNew = outputs(selected) match {
                case output: AssetOutput    => output.copy(lockupScript = lockupScriptNew)
                case output: ContractOutput => output.copy(lockupScript = lockupScriptNew)
              }
              val outputsNew = outputs.replace(selected, outputNew)
              tx.unsigned.copy(fixedOutputs = outputsNew)
            }
          forAll(localUnsignedGen) { unsignedNew =>
            val txNew = tx.copy(unsigned = unsignedNew)
            failCheck(checkChainIndex(txNew), InvalidOutputGroupIndex)
            failValidation(validateMempoolTx(txNew, blockFlow), InvalidOutputGroupIndex)
            failCheck(checkBlockTx(txNew, prepareWorldState(preOutputs)), InvalidOutputGroupIndex)
          }
        }
    }
  }

  it should "check distinction of inputs" in new StatelessFixture {
    forAll(transactionGenWithPreOutputs(1, 3)) {
      case (tx, preOutputs) =>
        val inputs      = tx.unsigned.inputs
        val unsignedNew = tx.unsigned.copy(inputs = inputs ++ inputs)
        val txNew       = tx.copy(unsigned = unsignedNew)
        failCheck(checkUniqueInputs(txNew), DoubleSpending)
        failValidation(validateMempoolTx(txNew, blockFlow), DoubleSpending)
        failCheck(checkBlockTx(txNew, prepareWorldState(preOutputs)), DoubleSpending)
    }
  }

  it should "check output data size" in new StatelessFixture {
    private def modifyData(outputs: AVector[TxOutput], index: Int): AVector[TxOutput] = {
      val dataNew = ByteString.fromArrayUnsafe(Array.fill(ALF.MaxOutputDataSize + 1)(0))
      dataNew.length is ALF.MaxOutputDataSize + 1
      val outputNew = outputs(index) match {
        case o: AssetOutput    => o.copy(additionalData = dataNew)
        case o: ContractOutput => o.copy(additionalData = dataNew)
      }
      outputs.replace(index, outputNew)
    }

    forAll(transactionGenWithPreOutputs(1, 3)) {
      case (tx, preOutputs) =>
        val outputIndex = Random.source.nextInt(tx.outputsLength)
        val txNew = if (outputIndex < tx.unsigned.fixedOutputs.length) {
          val outputsNew = modifyData(tx.unsigned.fixedOutputs, outputIndex)
          tx.copy(unsigned = tx.unsigned.copy(fixedOutputs = outputsNew))
        } else {
          val correctedIndex = outputIndex - tx.unsigned.fixedOutputs.length
          val outputsNew     = modifyData(tx.generatedOutputs, correctedIndex)
          tx.copy(generatedOutputs = outputsNew)
        }
        failCheck(checkOutputDataSize(txNew), OutputDataSizeExceeded)
        failValidation(validateMempoolTx(txNew, blockFlow), OutputDataSizeExceeded)
        failCheck(checkBlockTx(txNew, prepareWorldState(preOutputs)), OutputDataSizeExceeded)
    }
  }

  behavior of "stateful validation"

  trait StatefulFixture extends StatelessFixture {
    def genTokenOutput(tokenId: Hash, amount: U64): AssetOutput = {
      AssetOutput(U64.Zero,
                  0,
                  LockupScript.p2pkh(Hash.zero),
                  AVector(tokenId -> amount),
                  ByteString.empty)
    }

    def modifyTokenAmount(tx: Transaction, tokenId: TokenId, f: U64 => U64): Transaction = {
      val fixedOutputs = tx.unsigned.fixedOutputs
      val relatedOutputIndexes = fixedOutputs
        .mapWithIndex {
          case (output: AssetOutput, index) => (index, output.tokens.exists(_._1 equals tokenId))
          case (_, index)                   => (index, false)
        }
        .map(_._1)
      val selected    = relatedOutputIndexes.sample()
      val output      = fixedOutputs(selected).asInstanceOf[AssetOutput]
      val tokenIndex  = output.tokens.indexWhere(_._1 equals tokenId)
      val tokenAmount = output.tokens(tokenIndex)._2
      val outputNew =
        output.copy(tokens = output.tokens.replace(tokenIndex, tokenId -> f(tokenAmount)))
      tx.copy(unsigned = tx.unsigned.copy(fixedOutputs = fixedOutputs.replace(selected, outputNew)))
    }

    def sampleToken(tx: Transaction): TokenId = {
      val tokens = tx.unsigned.fixedOutputs.flatMap {
        case output: AssetOutput => output.tokens.map(_._1)
        case _: ContractOutput   => AVector.ofSize[TokenId](0)
      }
      tokens.sample()
    }

    def getTokenAmount(tx: Transaction, tokenId: TokenId): U64 = {
      tx.unsigned.fixedOutputs.fold(U64.Zero) {
        case (acc, output: AssetOutput) =>
          acc + output.tokens.filter(_._1 equals tokenId).map(_._2).reduce(_ + _)
        case (acc, _) => acc
      }
    }

    def replaceTokenId(tx: Transaction, from: TokenId, to: TokenId): Transaction = {
      val outputsNew = tx.unsigned.fixedOutputs.map[TxOutput] {
        case output: AssetOutput =>
          val tokensNew = output.tokens.map {
            case (id, amount) if id equals from => (to, amount)
            case pair                           => pair
          }
          output.copy(tokens = tokensNew)
        case output: ContractOutput => output
      }
      tx.copy(unsigned = tx.unsigned.copy(fixedOutputs = outputsNew))
    }
  }

  it should "get previous outputs of tx inputs" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs()) {
      case (tx, inputInfos) =>
        val worldStateNew = prepareWorldState(inputInfos)
        getPreOutputs(tx, worldStateNew) isE inputInfos.map(_.referredOutput)
    }
  }

  it should "test both ALF and token balances" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs()) {
      case (tx, preOutputs) =>
        passCheck(checkAlfBalance(tx, preOutputs.map(_.referredOutput)))
        passCheck(checkTokenBalance(tx, preOutputs.map(_.referredOutput)))
        passCheck(checkBlockTx(tx, prepareWorldState(preOutputs)))
    }
  }

  it should "validate ALF balances" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs()) {
      case (tx, preOutputs) =>
        val txNew = modifyAlfAmount(tx, 1)
        failCheck(checkAlfBalance(txNew, preOutputs.map(_.referredOutput)), InvalidAlfBalance)
        failCheck(checkBlockTx(txNew, prepareWorldState(preOutputs)), InvalidAlfBalance)
    }
  }

  it should "test token balance overflow" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs(issueNewToken = false)) {
      case (tx, preOutputs) =>
        whenever(tx.unsigned.fixedOutputs.length >= 2) { // only able to overflow 2 outputs
          val tokenId     = sampleToken(tx)
          val tokenAmount = getTokenAmount(tx, tokenId)
          val txNew       = modifyTokenAmount(tx, tokenId, U64.MaxValue - tokenAmount + 1 + _)
          failCheck(checkTokenBalance(txNew, preOutputs.map(_.referredOutput)), BalanceOverFlow)
          failCheck(checkBlockTx(txNew, prepareWorldState(preOutputs)), BalanceOverFlow)
        }
    }
  }

  it should "validate token balances" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs(issueNewToken = false)) {
      case (tx, preOutputs) =>
        val tokenId = sampleToken(tx)
        val txNew   = modifyTokenAmount(tx, tokenId, _ + 1)
        failCheck(checkTokenBalance(txNew, preOutputs.map(_.referredOutput)), InvalidTokenBalance)
        failCheck(checkBlockTx(txNew, prepareWorldState(preOutputs)), InvalidTokenBalance)
    }
  }

  it should "create new token" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs()) {
      case (tx, preOutputs) =>
        val newTokenId = tx.newTokenId
        val newTokenIssued = tx.unsigned.fixedOutputs.exists {
          case output: AssetOutput => output.tokens.exists(_._1 equals newTokenId)
          case _                   => false
        }
        newTokenIssued is true

        val txNew0 = replaceTokenId(tx, tx.newTokenId, Hash.generate)
        failCheck(checkTokenBalance(txNew0, preOutputs.map(_.referredOutput)), InvalidTokenBalance)
        failCheck(checkBlockTx(txNew0, prepareWorldState(preOutputs)), InvalidTokenBalance)

        val tokenAmount = getTokenAmount(tx, newTokenId)
        val txNew1      = modifyTokenAmount(tx, newTokenId, U64.MaxValue - tokenAmount + 1 + _)
        failCheck(checkTokenBalance(txNew1, preOutputs.map(_.referredOutput)), BalanceOverFlow)
        failCheck(checkBlockTx(txNew1, prepareWorldState(preOutputs)), BalanceOverFlow)
    }
  }

  it should "validate witnesses" in new StatefulFixture {
    import ModelGenerators.ScriptPair
    forAll(transactionGenWithPreOutputs(1, 1)) {
      case (tx, preOutputs) =>
        val inputsState              = preOutputs.map(_.referredOutput)
        val ScriptPair(_, unlock, _) = p2pkScriptGen(GroupIndex.unsafe(1)).sample.get
        val unsigned                 = tx.unsigned
        val inputs                   = unsigned.inputs
        val preparedWorldState       = prepareWorldState(preOutputs)

        {
          val txNew = tx.copy(signatures = tx.signatures.init)
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
          val (sampleIndex, _) = tx.signatures.sampleWithIndex()
          val signaturesNew    = tx.signatures.replace(sampleIndex, signature)
          val txNew            = tx.copy(signatures = signaturesNew)
          failCheck(checkWitnesses(txNew, inputsState), InvalidSignature)
          failCheck(checkBlockTx(txNew, preparedWorldState), InvalidSignature)
        }
    }
  }
}
