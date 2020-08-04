package org.alephium.flow.core.validation

import akka.util.ByteString
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.crypto.{ED25519, ED25519Signature}
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.io.IOResult
import org.alephium.protocol.model._
import org.alephium.util.AVector

class BlockValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  import BlockValidation._

  def passCheck[T](result: BlockValidationResult[T]): Assertion = {
    result.isRight is true
  }

  def failCheck[T](result: BlockValidationResult[T], error: InvalidBlockStatus): Assertion = {
    result.left.value isE error
  }

  def passValidation(result: IOResult[BlockStatus]): Assertion = {
    result.toOption.get is ValidBlock
  }

  def failValidation(result: IOResult[BlockStatus], error: InvalidBlockStatus): Assertion = {
    result.toOption.get is error
  }

  it should "validate group for block" in {
    forAll(blockGenOf(config.brokerInfo)) { block =>
      passCheck(checkGroup(block))
    }
    forAll(blockGenNotOf(config.brokerInfo)) { block =>
      failCheck(checkGroup(block), InvalidGroup)
    }
  }

  it should "validate nonEmpty transaction list" in {
    val block0 = blockGen.retryUntil(_.transactions.nonEmpty).sample.get
    val block1 = block0.copy(transactions = AVector.empty)
    passCheck(checkNonEmptyTransactions(block0))
    failCheck(checkNonEmptyTransactions(block1), EmptyTransactionList)
  }

  it should "validate coinbase transaction" in {
    val (privateKey, publicKey) = ED25519.generatePriPub()
    val block0                  = Block.from(AVector.empty, AVector.empty, config.maxMiningTarget, 0)

    val input0          = txInputGen.sample.get
    val output0         = txOutputGen.sample.get
    val emptyInputs     = AVector.empty[TxInput]
    val emptyOutputs    = AVector.empty[TxOutput]
    val emptySignatures = AVector.empty[ED25519Signature]

    val coinbase1     = Transaction.coinbase(publicKey, 0, ByteString.empty)
    val testSignature = AVector(ED25519.sign(coinbase1.unsigned.hash.bytes, privateKey))
    val block1        = block0.copy(transactions = AVector(coinbase1))
    passCheck(checkCoinbase(block1))

    val coinbase2 = Transaction.from(AVector(input0), AVector(output0), emptySignatures)
    val block2    = block0.copy(transactions = AVector(coinbase2))
    failCheck(checkCoinbase(block2), InvalidCoinbase)

    val coinbase3 = Transaction.from(emptyInputs, emptyOutputs, testSignature)
    val block3    = block0.copy(transactions = AVector(coinbase3))
    failCheck(checkCoinbase(block3), InvalidCoinbase)

    val coinbase4 = Transaction.from(emptyInputs, AVector(output0), testSignature)
    val block4    = block0.copy(transactions = AVector(coinbase4))
    failCheck(checkCoinbase(block4), InvalidCoinbase)

    val coinbase5 = Transaction.from(AVector(input0), AVector(output0), emptySignatures)
    val block5    = block0.copy(transactions = AVector(coinbase5))
    failCheck(checkCoinbase(block5), InvalidCoinbase)

    val coinbase6 = Transaction.from(emptyInputs, emptyOutputs, emptySignatures)
    val block6    = block0.copy(transactions = AVector(coinbase6))
    failCheck(checkCoinbase(block6), InvalidCoinbase)

    val coinbase7 = Transaction.from(emptyInputs, emptyOutputs, AVector(output0), testSignature)
    val block7    = block0.copy(transactions = AVector(coinbase7))
    failCheck(checkCoinbase(block7), InvalidCoinbase)

    val coinbase8 = Transaction.from(emptyInputs, emptyOutputs, AVector(output0), emptySignatures)
    val block8    = block0.copy(transactions = AVector(coinbase8))
    failCheck(checkCoinbase(block8), InvalidCoinbase)
  }
}
