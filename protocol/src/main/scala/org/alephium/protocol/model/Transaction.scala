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
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.Serde
import org.alephium.util.{AVector, TimeStamp, U256}

sealed trait TransactionAbstract {
  def unsigned: UnsignedTransaction
  def inputSignatures: AVector[Signature]
  def contractSignatures: AVector[Signature]

  def hash: Hash = unsigned.hash

  // this might only works for validated tx
  def fromGroup(implicit config: GroupConfig): GroupIndex = unsigned.fromGroup

  // this might only works for validated tx
  def toGroup(implicit config: GroupConfig): GroupIndex = unsigned.toGroup

  // this might only works for validated tx
  def chainIndex(implicit config: GroupConfig): ChainIndex = ChainIndex(fromGroup, toGroup)

  def gasFeeUnsafe: U256 = unsigned.gasPrice.mulUnsafe(unsigned.startGas.toU256)
}

final case class Transaction(unsigned: UnsignedTransaction,
                             contractInputs: AVector[ContractOutputRef],
                             generatedOutputs: AVector[TxOutput],
                             inputSignatures: AVector[Signature],
                             contractSignatures: AVector[Signature])
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

  lazy val alfAmountInOutputs: Option[U256] = {
    val sum1Opt =
      unsigned.fixedOutputs
        .foldE(U256.Zero)((sum, output) => sum.add(output.amount).toRight(()))
        .toOption
    val sum2Opt =
      generatedOutputs
        .foldE(U256.Zero)((sum, output) => sum.add(output.amount).toRight(()))
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
    Serde.forProduct5(
      Transaction.apply,
      t =>
        (t.unsigned, t.contractInputs, t.generatedOutputs, t.inputSignatures, t.contractSignatures))

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
           inputSignatures: AVector[Signature]): Transaction = {
    Transaction(UnsignedTransaction(inputs, outputs),
                contractInputs   = AVector.empty,
                generatedOutputs = AVector.empty,
                inputSignatures,
                contractSignatures = AVector.empty)
  }

  def from(inputs: AVector[TxInput],
           outputs: AVector[AssetOutput],
           generatedOutputs: AVector[TxOutput],
           inputSignatures: AVector[Signature]): Transaction = {
    Transaction(UnsignedTransaction(inputs, outputs),
                contractInputs = AVector.empty,
                generatedOutputs,
                inputSignatures,
                contractSignatures = AVector.empty)
  }

  def from(unsigned: UnsignedTransaction, privateKey: PrivateKey): Transaction = {
    from(unsigned, AVector.empty[TxOutput], privateKey)
  }

  def from(unsigned: UnsignedTransaction,
           generatedOutputs: AVector[TxOutput],
           privateKey: PrivateKey): Transaction = {
    val inputCnt  = unsigned.inputs.length
    val signature = SignatureSchema.sign(unsigned.hash.bytes, privateKey)
    Transaction(unsigned,
                contractInputs = AVector.empty,
                generatedOutputs,
                AVector.fill(inputCnt)(signature),
                contractSignatures = AVector.empty)
  }

  def from(unsigned: UnsignedTransaction,
           contractInputs: AVector[ContractOutputRef],
           generatedOutputs: AVector[TxOutput],
           privateKey: PrivateKey): Transaction = {
    val inputCnt  = unsigned.inputs.length
    val signature = SignatureSchema.sign(unsigned.hash.bytes, privateKey)
    Transaction(unsigned,
                contractInputs,
                generatedOutputs,
                AVector.fill(inputCnt)(signature),
                contractSignatures = AVector.empty)
  }

  def from(unsigned: UnsignedTransaction, inputSignatures: AVector[Signature]): Transaction = {
    Transaction(unsigned,
                contractInputs   = AVector.empty,
                generatedOutputs = AVector.empty,
                inputSignatures,
                contractSignatures = AVector.empty)
  }

  def coinbase(txs: AVector[Transaction],
               publicKey: PublicKey,
               data: ByteString,
               target: Target,
               blockTs: TimeStamp): Transaction = {
    val gasFee = txs.fold(U256.Zero)(_ addUnsafe _.gasFeeUnsafe)
    coinbase(gasFee, publicKey, data, target, blockTs)
  }

  def coinbase(gasFee: U256,
               publicKey: PublicKey,
               data: ByteString,
               target: Target,
               blockTs: TimeStamp): Transaction = {
    val pkScript = LockupScript.p2pkh(publicKey)
    val reward   = Emission.reward(target, blockTs, ALF.GenesisTimestamp)
    val txOutput = AssetOutput(reward.addUnsafe(gasFee), 0, pkScript, tokens = AVector.empty, data)
    val unsigned = UnsignedTransaction(AVector.empty, AVector(txOutput))
    Transaction(unsigned,
                contractInputs     = AVector.empty,
                generatedOutputs   = AVector.empty,
                inputSignatures    = AVector.empty,
                contractSignatures = AVector.empty)
  }

  def genesis(balances: AVector[(LockupScript, U256)]): Transaction = {
    val outputs = balances.map[AssetOutput] {
      case (lockupScript, value) => TxOutput.genesis(value, lockupScript)
    }
    val unsigned = UnsignedTransaction(inputs = AVector.empty, fixedOutputs = outputs)
    Transaction(unsigned,
                contractInputs     = AVector.empty,
                generatedOutputs   = AVector.empty,
                inputSignatures    = AVector.empty,
                contractSignatures = AVector.empty)
  }
}

final case class TransactionTemplate(unsigned: UnsignedTransaction,
                                     inputSignatures: AVector[Signature],
                                     contractSignatures: AVector[Signature])
    extends TransactionAbstract

object TransactionTemplate {
  def from(unsigned: UnsignedTransaction, privateKey: PrivateKey): TransactionTemplate = {
    val inputCnt  = unsigned.inputs.length
    val signature = SignatureSchema.sign(unsigned.hash.bytes, privateKey)
    TransactionTemplate(unsigned,
                        AVector.fill(inputCnt)(signature),
                        contractSignatures = AVector.empty)
  }
}
