package org.alephium.protocol.model

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519Signature}
import org.alephium.serde.{Serde, serialize}
import org.alephium.util.WithKeccak256

case class Transaction(
    unsigned: UnsignedTransaction,
    signature: ED25519Signature // TODO: support n2n transactions
) extends WithKeccak256[Transaction]

object Transaction {
  implicit val serde: Serde[Transaction] =
    Serde.forProduct2(Transaction.apply, t => (t.unsigned, t.signature))

  def from(unsigned: UnsignedTransaction, privateKey: ED25519PrivateKey): Transaction = {
    // TODO: check the privateKey are valid to spend all the txinputs
    val message   = serialize(unsigned)
    val signature = ED25519.sign(message, privateKey)
    Transaction(unsigned, signature)
  }
}
