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

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.{PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.serde.serialize
import org.alephium.util.{AVector, TimeStamp, U256}

class BlockValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  def passCheck[T](result: BlockValidationResult[T]): Assertion = {
    result.isRight is true
  }

  def failCheck[T](result: BlockValidationResult[T], error: InvalidBlockStatus): Assertion = {
    result.left.value isE error
  }

  def passValidation(result: BlockValidationResult[Unit]): Assertion = {
    result.isRight is true
  }

  def failValidation(result: BlockValidationResult[Unit], error: InvalidBlockStatus): Assertion = {
    result.left.value isE error
  }

  class Fixture extends BlockValidation.Impl()

  it should "validate group for block" in new Fixture {
    forAll(blockGenOf(brokerConfig)) { block =>
      passCheck(checkGroup(block))
    }
    forAll(blockGenNotOf(brokerConfig)) { block =>
      failCheck(checkGroup(block), InvalidGroup)
    }
  }

  it should "validate nonEmpty transaction list" in new Fixture {
    val block0 = blockGen.retryUntil(_.transactions.nonEmpty).sample.get
    val block1 = block0.copy(transactions = AVector.empty)
    passCheck(checkNonEmptyTransactions(block0))
    failCheck(checkNonEmptyTransactions(block1), EmptyTransactionList)
  }

  def coinbase(chainIndex: ChainIndex, gasFee: U256, publicKey: PublicKey): Transaction = {
    Transaction.coinbase(chainIndex, gasFee, publicKey, Target.Max, TimeStamp.zero)
  }

  it should "validate coinbase transaction simple format" in new Fixture {
    val (privateKey, publicKey) = SignatureSchema.generatePriPub()
    val block0 =
      Block.from(AVector.empty, AVector.empty, consensusConfig.maxMiningTarget, TimeStamp.zero, 0)

    val input0          = txInputGen.sample.get
    val output0         = assetOutputGen.sample.get
    val emptyInputs     = AVector.empty[TxInput]
    val emptyOutputs    = AVector.empty[AssetOutput]
    val emptySignatures = AVector.empty[Signature]

    val coinbase1     = coinbase(block0.chainIndex, 0, publicKey)
    val testSignature = AVector(SignatureSchema.sign(coinbase1.unsigned.hash.bytes, privateKey))
    val block1        = block0.copy(transactions = AVector(coinbase1))
    passCheck(checkCoinbaseEasy(block1))

    val coinbase2 = Transaction.from(AVector(input0), AVector(output0), emptySignatures)
    val block2    = block0.copy(transactions = AVector(coinbase2))
    failCheck(checkCoinbaseEasy(block2), InvalidCoinbaseFormat)

    val coinbase3 = Transaction.from(emptyInputs, emptyOutputs, testSignature)
    val block3    = block0.copy(transactions = AVector(coinbase3))
    failCheck(checkCoinbaseEasy(block3), InvalidCoinbaseFormat)

    val coinbase4 = Transaction.from(emptyInputs, AVector(output0), testSignature)
    val block4    = block0.copy(transactions = AVector(coinbase4))
    failCheck(checkCoinbaseEasy(block4), InvalidCoinbaseFormat)

    val coinbase5 = Transaction.from(AVector(input0), AVector(output0), emptySignatures)
    val block5    = block0.copy(transactions = AVector(coinbase5))
    failCheck(checkCoinbaseEasy(block5), InvalidCoinbaseFormat)

    val coinbase6 = Transaction.from(emptyInputs, emptyOutputs, emptySignatures)
    val block6    = block0.copy(transactions = AVector(coinbase6))
    failCheck(checkCoinbaseEasy(block6), InvalidCoinbaseFormat)

    val coinbase7 = Transaction.from(emptyInputs, emptyOutputs, AVector(output0), testSignature)
    val block7    = block0.copy(transactions = AVector(coinbase7))
    failCheck(checkCoinbaseEasy(block7), InvalidCoinbaseFormat)

    val coinbase8 = Transaction.from(emptyInputs, emptyOutputs, AVector(output0), emptySignatures)
    val block8    = block0.copy(transactions = AVector(coinbase8))
    failCheck(checkCoinbaseEasy(block8), InvalidCoinbaseFormat)
  }

  it should "check coinbase data" in new Fixture {
    val block        = blockGenOf(brokerConfig).sample.get
    val chainIndex   = block.chainIndex
    val coinbaseData = block.coinbase.unsigned.fixedOutputs.head.additionalData
    val expected     = serialize(CoinbaseFixedData.from(chainIndex, block.header.timestamp))
    coinbaseData.startsWith(expected) is true
  }

  it should "check coinbase reward" in new Fixture {
    val block = blockGenOf(brokerConfig).filter(_.nonCoinbase.nonEmpty).sample.get
    passCheck(checkCoinbaseReward(block))

    val miningReward      = brokerConfig.emission.miningReward(block.header)
    val coinbaseOutputNew = block.coinbase.unsigned.fixedOutputs.head.copy(amount = miningReward)
    val coinbaseNew = block.coinbase.copy(
      unsigned = block.coinbase.unsigned.copy(fixedOutputs = AVector(coinbaseOutputNew)))
    val txsNew   = block.transactions.replace(block.transactions.length - 1, coinbaseNew)
    val blockNew = block.copy(transactions = txsNew)
    failCheck(checkCoinbaseReward(blockNew), InvalidCoinbaseReward)
  }
}
