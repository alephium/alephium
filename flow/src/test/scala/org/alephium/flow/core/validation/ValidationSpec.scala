package org.alephium.flow.core.validation

import akka.util.ByteString
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.crypto.{ED25519, ED25519Signature}
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model._
import org.alephium.util.AVector

class ValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  import Validation._

  def check[T](res: BlockValidationResult[T]): Assertion = {
    res.isRight is true
  }

  def check[T](res: BlockValidationResult[T], error: InvalidBlockStatus): Assertion = {
    res.left.value isE error
  }

  def checkTx[T](res: TxValidationResult[T]): Assertion = {
    res.isRight is true
  }

  def checkTx[T](res: TxValidationResult[T], error: InvalidTxStatus): Assertion = {
    res.left.value isE error
  }

  it should "validate group for block" in {
    forAll(blockGenOf(config.brokerInfo)) { block =>
      check(Validation.checkGroup(block))
    }
    forAll(blockGenNotOf(config.brokerInfo)) { block =>
      check(Validation.checkGroup(block), InvalidGroup)
    }
  }

  it should "validate nonEmpty transaction list" in {
    val block0 = blockGen.retryUntil(_.transactions.nonEmpty).sample.get
    val block1 = block0.copy(transactions = AVector.empty)
    check(Validation.checkNonEmptyTransactions(block0))
    check(Validation.checkNonEmptyTransactions(block1), EmptyTransactionList)
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
    check(checkCoinbase(block1))

    val coinbase2 = Transaction.from(AVector(input0), AVector(output0), emptySignatures)
    val block2    = block0.copy(transactions = AVector(coinbase2))
    check(checkCoinbase(block2), InvalidCoinbase)

    val coinbase3 = Transaction.from(emptyInputs, emptyOutputs, testSignature)
    val block3    = block0.copy(transactions = AVector(coinbase3))
    check(checkCoinbase(block3), InvalidCoinbase)

    val coinbase4 = Transaction.from(emptyInputs, AVector(output0), testSignature)
    val block4    = block0.copy(transactions = AVector(coinbase4))
    check(checkCoinbase(block4), InvalidCoinbase)

    val coinbase5 = Transaction.from(AVector(input0), AVector(output0), emptySignatures)
    val block5    = block0.copy(transactions = AVector(coinbase5))
    check(checkCoinbase(block5), InvalidCoinbase)

    val coinbase6 = Transaction.from(emptyInputs, emptyOutputs, emptySignatures)
    val block6    = block0.copy(transactions = AVector(coinbase6))
    check(checkCoinbase(block6), InvalidCoinbase)

    val coinbase7 = Transaction.from(emptyInputs, emptyOutputs, AVector(output0), testSignature)
    val block7    = block0.copy(transactions = AVector(coinbase7))
    check(checkCoinbase(block7), InvalidCoinbase)

    val coinbase8 = Transaction.from(emptyInputs, emptyOutputs, AVector(output0), emptySignatures)
    val block8    = block0.copy(transactions = AVector(coinbase8))
    check(checkCoinbase(block8), InvalidCoinbase)
  }

  // TODO: Add more mocking test
}
