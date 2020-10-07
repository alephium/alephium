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

import akka.util.ByteString
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.io.IOResult
import org.alephium.protocol.{Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.util.AVector

class BlockValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
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

  it should "validate coinbase transaction" in new Fixture {
    val (privateKey, publicKey) = SignatureSchema.generatePriPub()
    val block0                  = Block.from(AVector.empty, AVector.empty, consensusConfig.maxMiningTarget, 0)

    val input0          = txInputGen.sample.get
    val output0         = assetOutputGen.sample.get
    val emptyInputs     = AVector.empty[TxInput]
    val emptyOutputs    = AVector.empty[AssetOutput]
    val emptySignatures = AVector.empty[Signature]

    val coinbase1     = Transaction.coinbase(publicKey, 0, ByteString.empty)
    val testSignature = AVector(SignatureSchema.sign(coinbase1.unsigned.hash.bytes, privateKey))
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
