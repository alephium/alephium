package org.alephium.flow.core.validation

import akka.util.ByteString
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.crypto.{ED25519, ED25519Signature}
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Duration, TimeStamp}

class ValidationSpec extends AlephiumFlowSpec {
  import Validation._
  import ValidationStatus._

  def check(res: BlockValidationResult): Assertion = {
    res.isRight is true
  }

  def check(res: BlockValidationResult, error: InvalidBlockStatus): Assertion = {
    res.left.value isE error
  }

  it should "validate group for block" in {
    forAll(ModelGen.blockGenFor(config.brokerInfo)) { block =>
      check(Validation.checkGroup(block))
    }
    forAll(ModelGen.blockGenNotFor(config.brokerInfo)) { block =>
      check(Validation.checkGroup(block), InvalidGroup)
    }
  }

  it should "validate timestamp for block" in {
    val header  = ModelGen.blockGen.sample.get.header
    val now     = TimeStamp.now()
    val before  = (now - Duration.ofMinutesUnsafe(61)).get
    val after   = now + Duration.ofMinutesUnsafe(61)
    val header0 = header.copy(timestamp = now)
    val header1 = header.copy(timestamp = before)
    val header2 = header.copy(timestamp = after)
    check(Validation.checkTimeStamp(header0, isSyncing = false))
    check(Validation.checkTimeStamp(header1, isSyncing = false), InvalidTimeStamp)
    check(Validation.checkTimeStamp(header2, isSyncing = false), InvalidTimeStamp)
    check(Validation.checkTimeStamp(header0, isSyncing = true))
    check(Validation.checkTimeStamp(header1, isSyncing = true))
    check(Validation.checkTimeStamp(header2, isSyncing = true), InvalidTimeStamp)
  }

  it should "validate nonEmpty transaction list" in {
    val block0 = ModelGen.blockGen.retryUntil(_.transactions.nonEmpty).sample.get
    val block1 = block0.copy(transactions = AVector.empty)
    check(Validation.checkNonEmptyTransactions(block0))
    check(Validation.checkNonEmptyTransactions(block1), EmptyTransactionList)
  }

  it should "validate coinbase transaction" in {
    val (privateKey, publicKey) = ED25519.generatePriPub()
    val block0                  = Block.from(AVector.empty, AVector.empty, config.maxMiningTarget, 0)

    val input0          = ModelGen.txInputGen.sample.get
    val output0         = ModelGen.txOutputGen.sample.get
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
    check(checkCoinbase(block8))
  }

  // TODO: Add more mocking test
}
