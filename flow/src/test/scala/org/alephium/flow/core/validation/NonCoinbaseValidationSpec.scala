package org.alephium.flow.core.validation

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.io.IOResult
import org.alephium.protocol.ALF
import org.alephium.protocol.model._
import org.alephium.protocol.model.ModelGenerators.{AssetInputInfo, ContractInfo, TxInputStateInfo}
import org.alephium.protocol.vm.{LockupScript, VMFactory, WorldState}
import org.alephium.util.{AVector, U64}

class NonCoinbaseValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  import NonCoinbaseValidation._

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

  behavior of "Stateless Validation"

  trait StatelessFixture {
    val blockFlow = BlockFlow.fromGenesisUnsafe(storages)
  }

  it should "check empty inputs" in new StatelessFixture {
    forAll(transactionGen(1, 1)) { tx =>
      val unsignedNew = tx.unsigned.copy(inputs = AVector.empty)
      val txNew       = tx.copy(unsigned        = unsignedNew)
      failCheck(checkInputNum(txNew), NoInputs)
      failValidation(validateMempoolTx(txNew, blockFlow), NoInputs)
    }
  }

  it should "check empty outputs" in new StatelessFixture {
    forAll(transactionGen(1, 1)) { tx =>
      val unsignedNew = tx.unsigned.copy(fixedOutputs = AVector.empty)
      val txNew       = tx.copy(unsigned              = unsignedNew)
      failCheck(checkOutputNum(txNew), NoOutputs)
      failValidation(validateMempoolTx(txNew, blockFlow), NoOutputs)
    }
  }

  def genAlfOutput(amount: U64): AssetOutput = {
    TxOutput.asset(amount, 0, LockupScript.p2pkh(ALF.Hash.zero))
  }

  def modifyAlfAmount(tx: Transaction, delta: U64): Transaction = {
    val (index, output) = tx.unsigned.fixedOutputs.sampleWithIndex()
    val outputNew = output match {
      case o: AssetOutput    => o.copy(amount = o.amount + delta)
      case o: ContractOutput => o.copy(amount = o.amount + delta)
    }
    tx.copy(unsigned =
      tx.unsigned.copy(fixedOutputs = tx.unsigned.fixedOutputs.replace(index, outputNew)))
  }

  it should "check ALF balance overflow" in new StatelessFixture {
    forAll(transactionGen()) { tx =>
      whenever(tx.unsigned.fixedOutputs.length >= 2) { // only able to overflow 2 outputs
        val alfAmount = tx.alfAmountInOutputs.get
        val delta     = U64.MaxValue - alfAmount + 1
        val txNew     = modifyAlfAmount(tx, delta)
        passCheck(checkAlfOutputAmount(tx))
        failCheck(checkAlfOutputAmount(txNew), BalanceOverFlow)
        failValidation(validateMempoolTx(txNew, blockFlow), BalanceOverFlow)
      }
    }
  }

  it should "check the inputs indexes" in new StatelessFixture {
    forAll(transactionGen(2, 5)) { tx =>
      passCheck(checkChainIndex(tx))

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
      }
    }
  }

  it should "check the output indexes" in new StatelessFixture {
    forAll(transactionGen(2, 5)) { tx =>
      passCheck(checkChainIndex(tx))

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
        }
      }
    }
  }

  it should "check distinction of inputs" in new StatelessFixture {
    forAll(transactionGen(1, 3)) { tx =>
      passCheck(checkUniqueInputs(tx))

      val inputs      = tx.unsigned.inputs
      val unsignedNew = tx.unsigned.copy(inputs = inputs ++ inputs)
      val txNew       = tx.copy(unsigned = unsignedNew)
      failCheck(checkUniqueInputs(txNew), DoubleSpending)
      failValidation(validateMempoolTx(txNew, blockFlow), DoubleSpending)
    }
  }

  behavior of "stateful validation"

  trait StatefulFixture extends VMFactory {
    lazy val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

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

    def genTokenOutput(tokenId: ALF.Hash, amount: U64): AssetOutput = {
      AssetOutput(U64.Zero,
                  AVector(tokenId -> amount),
                  0,
                  LockupScript.p2pkh(ALF.Hash.zero),
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

  it should "test both ALF and token balances" in {
    forAll(transactionGenWithPreOutputs()) {
      case (tx, preOutput) =>
        passCheck(checkBalance(tx, preOutput.map(_.referredOutput)))
    }
  }

  it should "validate ALF balances" in {
    forAll(transactionGenWithPreOutputs()) {
      case (tx, preOutputs) =>
        val txNew = modifyAlfAmount(tx, 1)
        failCheck(checkAlfBalance(txNew, preOutputs.map(_.referredOutput)), InvalidAlfBalance)
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
        }
    }
  }

  it should "validate token balances" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs(issueNewToken = false)) {
      case (tx, preOutputs) =>
        val tokenId = sampleToken(tx)
        val txNew   = modifyTokenAmount(tx, tokenId, _ + 1)
        failCheck(checkTokenBalance(txNew, preOutputs.map(_.referredOutput)), InvalidTokenBalance)
    }
  }

  it should "create new token" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs(issueNewToken = true)) {
      case (tx, preOutputs) =>
        val newTokenId = tx.newTokenId
        val newTokenIssued = tx.unsigned.fixedOutputs.exists {
          case output: AssetOutput => output.tokens.exists(_._1 equals newTokenId)
          case _                   => false
        }
        newTokenIssued is true

        val txNew0 = replaceTokenId(tx, tx.newTokenId, ALF.Hash.generate)
        failCheck(checkTokenBalance(txNew0, preOutputs.map(_.referredOutput)), InvalidTokenBalance)

        val tokenAmount = getTokenAmount(tx, newTokenId)
        val txNew1      = modifyTokenAmount(tx, newTokenId, U64.MaxValue - tokenAmount + 1 + _)
        failCheck(checkTokenBalance(txNew1, preOutputs.map(_.referredOutput)), BalanceOverFlow)
    }
  }
}
