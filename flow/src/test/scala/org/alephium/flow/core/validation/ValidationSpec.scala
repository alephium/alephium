package org.alephium.flow.core.validation

import akka.util.ByteString
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.crypto.{ED25519, ED25519Signature}
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.ALF
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, Duration, TimeStamp, U64}

class ValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  import Validation._
  import NonCoinbaseValidation._

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

  it should "validate timestamp for block" in {
    val header  = blockGen.sample.get.header
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

  def genAlfOutput(amount: U64): AssetOutput = {
    TxOutput.asset(amount, 0, LockupScript.p2pkh(ALF.Hash.zero))
  }

  it should "test both ALF and token balances" in {
    forAll(transactionGenWithPreOutputs) {
      case (tx, preOutput) =>
        checkTx(checkBalance(tx, preOutput.map(_.referredOutput)))
    }
  }

  it should "validate ALF balances" in {
    val sum       = U64.MaxValue.subUnsafe(U64.Two)
    val preOutput = genAlfOutput(sum)
    val output1   = genAlfOutput(sum.subOneUnsafe())
    val output2   = genAlfOutput(U64.One)
    val output3   = genAlfOutput(U64.Two)
    val input     = txInputGen.sample.get
    val tx0 =
      Transaction.from(AVector(input), AVector(output1, output2), signatures = AVector.empty)
    val tx1 =
      Transaction.from(AVector(input), AVector(output1, output3), signatures = AVector.empty)
    checkTx(checkBalance(tx0, AVector(preOutput)))
    checkTx(checkBalance(tx1, AVector(preOutput)), InvalidAlfBalance)
  }

  def genTokenOutput(tokenId: ALF.Hash, amount: U64): AssetOutput = {
    AssetOutput(U64.Zero,
                AVector(tokenId -> amount),
                0,
                LockupScript.p2pkh(ALF.Hash.zero),
                ByteString.empty)
  }

  it should "test token balance overflow" in {
    val input     = txInputGen.sample.get
    val tokenId   = ALF.Hash.generate
    val preOutput = genTokenOutput(tokenId, U64.MaxValue)
    val output1   = genTokenOutput(tokenId, U64.MaxValue)
    val output2   = genTokenOutput(tokenId, U64.Zero)
    val output3   = genTokenOutput(tokenId, U64.One)
    val tx0 =
      Transaction.from(AVector(input), AVector(output1, output2), signatures = AVector.empty)
    val tx1 =
      Transaction.from(AVector(input), AVector(output1, output3), signatures = AVector.empty)
    checkTx(checkBalance(tx0, AVector(preOutput)))
    checkTx(checkBalance(tx1, AVector(preOutput)), BalanceOverFlow)
  }

  it should "validate token balances" in {
    val input     = txInputGen.sample.get
    val tokenId   = ALF.Hash.generate
    val sum       = U64.MaxValue.subUnsafe(U64.Two)
    val preOutput = genTokenOutput(tokenId, sum)
    val output1   = genTokenOutput(tokenId, sum.subOneUnsafe())
    val output2   = genTokenOutput(tokenId, U64.One)
    val output3   = genTokenOutput(tokenId, U64.Two)
    val tx0 =
      Transaction.from(AVector(input), AVector(output1, output2), signatures = AVector.empty)
    val tx1 =
      Transaction.from(AVector(input), AVector(output1, output3), signatures = AVector.empty)
    checkTx(checkBalance(tx0, AVector(preOutput)))
    checkTx(checkBalance(tx1, AVector(preOutput)), InvalidTokenBalance)
  }

  it should "create new token" in {
    val input   = txInputGen.sample.get
    val tokenId = input.hash

    val output0 = genTokenOutput(tokenId, U64.MaxValue)
    val tx0     = Transaction.from(AVector(input), AVector(output0), signatures = AVector.empty)
    checkTx(checkBalance(tx0, AVector.empty))

    val output1 = genTokenOutput(ALF.Hash.generate, U64.MaxValue)
    val tx1     = Transaction.from(AVector(input), AVector(output1), signatures = AVector.empty)
    checkTx(checkBalance(tx1, AVector.empty), InvalidTokenBalance)
  }

  // TODO: Add more mocking test
}
