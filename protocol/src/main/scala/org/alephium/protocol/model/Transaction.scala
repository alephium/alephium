package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.crypto._
import org.alephium.serde.{serialize, Serde}
import org.alephium.util.AVector

/*
 * For the moment, if a transaction does not have any input, then it's a coinbase transaction
 * In this way, we could pad many coinbase transactions into one block without hacking any code
 * TODO: we will evolve it to use only one coinbase transaction
 *
 */
case class Transaction(
    unsigned: UnsignedTransaction,
    data: ByteString,
    signature: ED25519Signature // TODO: support n2n transactions
) extends Keccak256Hash[Transaction] {
  override val hash: Keccak256 = _getHash
}

object Transaction {
  implicit val serde: Serde[Transaction] =
    Serde.forProduct3(Transaction.apply, t => (t.unsigned, t.data, t.signature))

  def from(inputs: AVector[TxOutputPoint],
           outputs: AVector[TxOutput],
           privateKey: ED25519PrivateKey): Transaction = {
    from(UnsignedTransaction(inputs, outputs), privateKey)
  }

  def from(unsigned: UnsignedTransaction, privateKey: ED25519PrivateKey): Transaction = {
    // TODO: check the privateKey are valid to spend all the txinputs
    val message   = serialize(unsigned)
    val signature = ED25519.sign(message, privateKey)
    Transaction(unsigned, ByteString.empty, signature)
  }

  def coinbase(address: ED25519PublicKey, value: BigInt, data: ByteString): Transaction = {
    val txOutput = TxOutput(value, address)
    val unsigned = UnsignedTransaction(AVector.empty, AVector(txOutput))
    Transaction(unsigned, data, ED25519Signature.zero)
  }

  def genesis(balances: AVector[(ED25519PublicKey, BigInt)]): Transaction = {
    val outputs  = balances.map { case (key, value) => TxOutput(value, key) }
    val unsigned = UnsignedTransaction(AVector.empty, outputs)
    Transaction(unsigned, ByteString.empty, ED25519Signature.zero)
  }
}
