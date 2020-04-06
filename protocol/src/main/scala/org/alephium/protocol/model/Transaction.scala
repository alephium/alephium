package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.crypto._
import org.alephium.protocol.ALF
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.script.{PubScript, Witness}
import org.alephium.serde.Serde
import org.alephium.util.AVector

/*
 * For the moment, if a transaction does not have any input, then it's a coinbase transaction
 * In this way, we could pad many coinbase transactions into one block without hacking any code
 *
 */
final case class Transaction(
    unsigned: UnsignedTransaction,
    witnesses: AVector[Witness]
) extends ALF.HashSerde[Transaction] {
  override val hash: ALF.Hash = unsigned.hash

  def fromGroup(implicit config: GroupConfig): GroupIndex  = unsigned.inputs.head.fromGroup
  def toGroup(implicit config: GroupConfig): GroupIndex    = unsigned.outputs.head.toGroup
  def chainIndex(implicit config: GroupConfig): ChainIndex = ChainIndex(fromGroup, toGroup)
}

object Transaction {
  implicit val serde: Serde[Transaction] =
    Serde.forProduct2(Transaction.apply, t => (t.unsigned, t.witnesses))

  def from(inputs: AVector[TxOutputPoint],
           outputs: AVector[TxOutput],
           publicKey: ED25519PublicKey,
           privateKey: ED25519PrivateKey): Transaction = {
    from(UnsignedTransaction(inputs, outputs, ByteString.empty), publicKey, privateKey)
  }

  def from(inputs: AVector[TxOutputPoint],
           outputs: AVector[TxOutput],
           witnesses: AVector[Witness]): Transaction = {
    Transaction(UnsignedTransaction(inputs, outputs, ByteString.empty), witnesses)
  }

  def from(unsigned: UnsignedTransaction,
           publicKey: ED25519PublicKey,
           privateKey: ED25519PrivateKey): Transaction = {
    // TODO: check the privateKey are valid to spend all the txinputs
    val witness = Witness.p2pkh(unsigned, publicKey, privateKey)
    Transaction(unsigned, AVector(witness))
  }

  def coinbase(publicKey: ED25519PublicKey, data: ByteString): Transaction = {
    val pkScript = PubScript.p2pkh(publicKey)
    val txOutput = TxOutput(ALF.CoinBaseValue, pkScript)
    val unsigned = UnsignedTransaction(AVector.empty, AVector(txOutput), data)
    Transaction(unsigned, AVector.empty)
  }

  def genesis(balances: AVector[(ED25519PublicKey, BigInt)]): Transaction = {
    val outputs = balances.map {
      case (publicKey, value) =>
        val pkScript = PubScript.p2pkh(publicKey)
        TxOutput(value, pkScript)
    }
    val unsigned = UnsignedTransaction(AVector.empty, outputs, ByteString.empty)
    Transaction(unsigned, AVector.empty)
  }

  def simpleTransfer(inputs: AVector[TxOutputPoint],
                     inputSum: BigInt,
                     from: ED25519PublicKey,
                     to: ED25519PublicKey,
                     value: BigInt,
                     privateKey: ED25519PrivateKey): Transaction = {
    val unsigned = UnsignedTransaction.simpleTransfer(inputs, inputSum, from, to, value)

    val witness = Witness.p2pkh(unsigned, from, privateKey)
    Transaction(unsigned, AVector.fill(inputs.length)(witness))
  }
}
