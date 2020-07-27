package org.alephium.flow.core.validation

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
    forAll(transactionGen(1, 1)()) { tx =>
      val unsignedNew = tx.unsigned.copy(inputs = AVector.empty)
      val txNew       = tx.copy(unsigned        = unsignedNew)
      failCheck(checkInputNum(txNew), NoInputs)
      failValidation(validateMempoolTx(txNew, blockFlow), NoInputs)
    }
  }

  it should "check empty outputs" in new StatelessFixture {
    forAll(transactionGen(1, 1)()) { tx =>
      val unsignedNew = tx.unsigned.copy(fixedOutputs = AVector.empty)
      val txNew       = tx.copy(unsigned              = unsignedNew)
      failCheck(checkOutputNum(txNew), NoOutputs)
      failValidation(validateMempoolTx(txNew, blockFlow), NoOutputs)
    }
  }

  def genAlfOutput(amount: U64): AssetOutput = {
    TxOutput.asset(amount, 0, LockupScript.p2pkh(ALF.Hash.zero))
  }

  it should "check ALF balance overflow" in new StatelessFixture {
    val output1 = genAlfOutput(U64.MaxValue)
    val output2 = genAlfOutput(U64.Zero)
    val output3 = genAlfOutput(U64.One)
    val input   = txInputGen.sample.get
    val tx0 =
      Transaction.from(AVector(input), AVector(output1, output2), signatures = AVector.empty)
    val tx1 =
      Transaction.from(AVector(input), AVector(output1, output3), signatures = AVector.empty)
    passCheck(checkAlfOutputAmount(tx0))
    failCheck(checkAlfOutputAmount(tx1), BalanceOverFlow)
    failValidation(validateMempoolTx(tx1, blockFlow), BalanceOverFlow)
  }

  it should "check the inputs indexes" in new StatelessFixture {
    forAll(transactionGen(2, 5)()) { tx =>
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
    forAll(transactionGen(2, 5)()) { tx =>
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
    forAll(transactionGen(1, 3)()) { tx =>
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
    val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

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

  it should "get previous outputs of tx inputs" in new StatefulFixture {
    forAll(transactionGenWithPreOutputs) {
      case (tx, inputInfos) =>
        val worldStateNew = prepareWorldState(inputInfos)
        getPreOutputs(tx, worldStateNew) isE inputInfos.map(_.referredOutput)
    }
  }
}
