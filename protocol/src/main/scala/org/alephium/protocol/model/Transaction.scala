package org.alephium.protocol.model

import org.alephium.crypto._
import org.alephium.serde.{serialize, Serde}
import org.alephium.util.AVector

case class Transaction(
    unsigned: UnsignedTransaction,
    signature: ED25519Signature // TODO: support n2n transactions
) extends WithKeccak256[Transaction] {
  override val hash: Keccak256 = Keccak256.hash(serialize[Transaction](this))
}

object Transaction {
  implicit val serde: Serde[Transaction] =
    Serde.forProduct2(Transaction.apply, t => (t.unsigned, t.signature))

  def from(unsigned: UnsignedTransaction, privateKey: ED25519PrivateKey): Transaction = {
    // TODO: check the privateKey are valid to spend all the txinputs
    val message   = serialize(unsigned)
    val signature = ED25519.sign(message, privateKey)
    Transaction(unsigned, signature)
  }

  def coinbase(address: ED25519PublicKey, value: BigInt): Transaction = {
    val txOutput = TxOutput(value, address)
    val unsigned = UnsignedTransaction(AVector.empty, AVector(txOutput))
    Transaction(unsigned, ED25519Signature.zero)
  }
}
