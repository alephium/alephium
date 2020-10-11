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

package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.protocol._
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.Serde
import org.alephium.util.{AVector, U64}

trait TransactionAbstract {
  def unsigned: UnsignedTransaction
  def signatures: AVector[Signature]

  def hash: Hash = unsigned.hash

  // this might only works for validated tx
  def fromGroup(implicit config: GroupConfig): GroupIndex = unsigned.fromGroup

  // this might only works for validated tx
  def toGroup(implicit config: GroupConfig): GroupIndex = unsigned.toGroup

  // this might only works for validated tx
  def chainIndex(implicit config: GroupConfig): ChainIndex = ChainIndex(fromGroup, toGroup)

  def newTokenId: TokenId = unsigned.inputs.head.hash
}

final case class Transaction(unsigned: UnsignedTransaction,
                             contractInputs: AVector[ContractOutputRef],
                             generatedOutputs: AVector[TxOutput],
                             signatures: AVector[Signature])
    extends HashSerde[Transaction]
    with TransactionAbstract {
  override val hash: Hash = unsigned.hash

  def allOutputs: AVector[TxOutput] = unsigned.fixedOutputs.as[TxOutput] ++ generatedOutputs

  def outputsLength: Int = unsigned.fixedOutputs.length + generatedOutputs.length

  def getOutput(index: Int): TxOutput = {
    assume(index >= 0 && index < outputsLength)
    if (index < unsigned.fixedOutputs.length) {
      unsigned.fixedOutputs(index)
    } else {
      generatedOutputs(index - unsigned.fixedOutputs.length)
    }
  }

  lazy val alfAmountInOutputs: Option[U64] = {
    val sum1Opt =
      unsigned.fixedOutputs
        .foldE(U64.Zero)((sum, output) => sum.add(output.amount).toRight(()))
        .toOption
    val sum2Opt =
      generatedOutputs
        .foldE(U64.Zero)((sum, output) => sum.add(output.amount).toRight(()))
        .toOption
    for {
      sum1 <- sum1Opt
      sum2 <- sum2Opt
      sum  <- sum1.add(sum2)
    } yield sum
  }
}

object Transaction {
  implicit val serde: Serde[Transaction] =
    Serde.forProduct4(Transaction.apply,
                      t => (t.unsigned, t.contractInputs, t.generatedOutputs, t.signatures))

  def from(inputs: AVector[TxInput],
           outputs: AVector[AssetOutput],
           generatedOutputs: AVector[TxOutput],
           privateKey: PrivateKey): Transaction = {
    from(UnsignedTransaction(inputs, outputs), generatedOutputs, privateKey)
  }

  def from(inputs: AVector[TxInput],
           outputs: AVector[AssetOutput],
           privateKey: PrivateKey): Transaction = {
    from(inputs, outputs, AVector.empty, privateKey)
  }

  def from(inputs: AVector[TxInput],
           outputs: AVector[AssetOutput],
           signatures: AVector[Signature]): Transaction = {
    Transaction(UnsignedTransaction(inputs, outputs),
                contractInputs   = AVector.empty,
                generatedOutputs = AVector.empty,
                signatures)
  }

  def from(inputs: AVector[TxInput],
           outputs: AVector[AssetOutput],
           generatedOutputs: AVector[TxOutput],
           signatures: AVector[Signature]): Transaction = {
    Transaction(UnsignedTransaction(inputs, outputs), AVector.empty, generatedOutputs, signatures)
  }

  def from(unsigned: UnsignedTransaction, privateKey: PrivateKey): Transaction = {
    from(unsigned, AVector.empty[TxOutput], privateKey)
  }

  def from(unsigned: UnsignedTransaction,
           generatedOutputs: AVector[TxOutput],
           privateKey: PrivateKey): Transaction = {
    val inputCnt  = unsigned.inputs.length
    val signature = SignatureSchema.sign(unsigned.hash.bytes, privateKey)
    Transaction(unsigned, AVector.empty, generatedOutputs, AVector.fill(inputCnt)(signature))
  }

  def from(unsigned: UnsignedTransaction, signatures: AVector[Signature]): Transaction = {
    Transaction(unsigned, AVector.empty, AVector.empty, signatures)
  }

  def coinbase(publicKey: PublicKey, height: Int, data: ByteString): Transaction = {
    val pkScript = LockupScript.p2pkh(publicKey)
    val txOutput = AssetOutput(ALF.CoinBaseValue, height, pkScript, tokens = AVector.empty, data)
    val unsigned = UnsignedTransaction(AVector.empty, AVector(txOutput))
    Transaction(unsigned,
                contractInputs   = AVector.empty,
                generatedOutputs = AVector.empty,
                signatures       = AVector.empty)
  }

  def genesis(balances: AVector[(LockupScript, U64)]): Transaction = {
    val outputs = balances.map[AssetOutput] {
      case (lockupScript, value) => TxOutput.genesis(value, lockupScript)
    }
    val unsigned = UnsignedTransaction(inputs = AVector.empty, fixedOutputs = outputs)
    Transaction(unsigned,
                contractInputs   = AVector.empty,
                generatedOutputs = AVector.empty,
                signatures       = AVector.empty)
  }
}

final case class TransactionTemplate(unsigned: UnsignedTransaction, signatures: AVector[Signature])
    extends TransactionAbstract

object TransactionTemplate {
  def from(unsigned: UnsignedTransaction, privateKey: PrivateKey): TransactionTemplate = {
    val inputCnt  = unsigned.inputs.length
    val signature = SignatureSchema.sign(unsigned.hash.bytes, privateKey)
    TransactionTemplate(unsigned, AVector.fill(inputCnt)(signature))
  }
}
