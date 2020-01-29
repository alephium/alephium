package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.crypto._
import org.alephium.protocol.script.{PubScript, Witness}
import org.alephium.serde.Serde
import org.alephium.util.AVector

/*
 * For the moment, if a transaction does not have any input, then it's a coinbase transaction
 * In this way, we could pad many coinbase transactions into one block without hacking any code
 * TODO: we will evolve it to use only one coinbase transaction
 *
 */
case class Transaction(
    raw: RawTransaction,
    data: ByteString,
    witnesses: AVector[Witness] // TODO: support n2n transactions
) extends Keccak256Hash[Transaction] {
  override val hash: Keccak256 = _getHash
}

object Transaction {
  implicit val serde: Serde[Transaction] =
    Serde.forProduct3(Transaction.apply, t => (t.raw, t.data, t.witnesses))

  def from(inputs: AVector[TxOutputPoint],
           outputs: AVector[TxOutput],
           publicKey: ED25519PublicKey,
           privateKey: ED25519PrivateKey): Transaction = {
    from(RawTransaction(inputs, outputs), publicKey, privateKey)
  }

  def from(inputs: AVector[TxOutputPoint],
           outputs: AVector[TxOutput],
           witnesses: AVector[Witness]): Transaction = {
    Transaction(RawTransaction(inputs, outputs), ByteString.empty, witnesses)
  }

  def from(raw: RawTransaction,
           publicKey: ED25519PublicKey,
           privateKey: ED25519PrivateKey): Transaction = {
    // TODO: check the privateKey are valid to spend all the txinputs
    val witness = Witness.p2pkh(raw, publicKey, privateKey)
    Transaction(raw, ByteString.empty, AVector(witness))
  }

  def coinbase(publicKey: ED25519PublicKey, value: BigInt, data: ByteString): Transaction = {
    val pkScript = PubScript.p2pkh(publicKey)
    val txOutput = TxOutput(value, pkScript)
    val raw      = RawTransaction(AVector.empty, AVector(txOutput))
    Transaction(raw, data, AVector.empty)
  }

  def genesis(balances: AVector[(ED25519PublicKey, BigInt)]): Transaction = {
    val outputs = balances.map {
      case (publicKey, value) =>
        val pkScript = PubScript.p2pkh(publicKey)
        TxOutput(value, pkScript)
    }
    val raw = RawTransaction(AVector.empty, outputs)
    Transaction(raw, ByteString.empty, AVector.empty)
  }
}
