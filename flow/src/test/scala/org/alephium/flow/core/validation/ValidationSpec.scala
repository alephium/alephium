package org.alephium.flow.core.validation

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.crypto.{ED25519, ED25519Signature}
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model.{ModelGen, Transaction}
import org.alephium.util.{AVector, Duration, TimeStamp}

class ValidationSpec extends AlephiumFlowSpec {
  import Validation._
  import ValidationStatus._

  def check(res: BlockValidationResult): Assertion = {
    res.isRight is true
  }

  def check(res: BlockValidationResult, error: InvalidBlockStatus): Assertion = {
    res.left.value.right.value is error
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
    val before  = now + Duration.ofMinutes(-61)
    val after   = now + Duration.ofMinutes(61)
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
    val block0 = ModelGen.blockGen
      .retryUntil { block =>
        val txs = block.transactions
        txs.nonEmpty && {
          val unsigned = txs.head.unsigned
          unsigned.inputs.nonEmpty && unsigned.outputs.length > 1
        }
      }
      .sample
      .get
    val (privateKey, _) = ED25519.generatePriPub()

    val coinbase0 = block0.transactions.head
    val input0    = coinbase0.unsigned.inputs
    val output0   = coinbase0.unsigned.outputs
    check(checkCoinbase(block0), InvalidCoinbase)

    val coinbase1 =
      Transaction.from0(AVector.empty, AVector(output0.head), privateKey)
    val block1 = block0.copy(transactions = AVector(coinbase1))
    check(checkCoinbase(block1), InvalidCoinbase)

    val coinbase2 = Transaction.from0(AVector(input0.head), AVector(output0.head), privateKey)
    val block2    = block0.copy(transactions = AVector(coinbase2))
    check(checkCoinbase(block2), InvalidCoinbase)

    val coinbase3 =
      Transaction.from0(AVector.empty, AVector.empty, privateKey)
    val block3 = block0.copy(transactions = AVector(coinbase3))
    check(checkCoinbase(block3), InvalidCoinbase)

    val coinbase4 =
      Transaction.from1(AVector.empty, AVector(output0.head), ED25519Signature.zero)
    val block4 = block0.copy(transactions = AVector(coinbase4))
    check(checkCoinbase(block4))

    val coinbase5 =
      Transaction.from1(AVector(input0.head), AVector(output0.head), ED25519Signature.zero)
    val block5 = block0.copy(transactions = AVector(coinbase5))
    check(checkCoinbase(block5), InvalidCoinbase)

    val coinbase6 =
      Transaction.from1(AVector.empty, AVector.empty, ED25519Signature.zero)
    val block6 = block0.copy(transactions = AVector(coinbase6))
    check(checkCoinbase(block6), InvalidCoinbase)
  }

  // TODO: Add more mocking test
}
