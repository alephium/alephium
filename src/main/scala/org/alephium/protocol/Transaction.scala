package org.alephium.protocol

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519Signature}
import org.alephium.serde._

case class Transaction(
    unsigned: UnsignedTransaction,
    signature: ED25519Signature // TODO: support n2n transactions
)

object Transaction {
  def from(unsigned: UnsignedTransaction, privateKey: ED25519PrivateKey): Transaction = {
    // TODO: check the privateKey are valid to spend all the txinputs
    val message   = serialize(unsigned)
    val signature = ED25519.sign(message, privateKey)
    Transaction(unsigned, signature)
  }
}
