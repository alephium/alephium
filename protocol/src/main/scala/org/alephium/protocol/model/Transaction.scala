package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.crypto._
import org.alephium.protocol.script.{Script, Witness}
import org.alephium.serde.Serde
import org.alephium.util.AVector

/*
 * For the moment, if a transaction does not have any input, then it's a coinbase transaction
 * In this way, we could pad many coinbase transactions into one block without hacking any code
 * TODO: we will evolve it to use only one coinbase transaction
 *
 */
case class Transaction(
    unsigned: RawTransaction,
    data: ByteString,
    witness: AVector[Witness] // TODO: support n2n transactions
) extends Keccak256Hash[Transaction] {
  override val hash: Keccak256 = _getHash
}

object Transaction {
  implicit val serde: Serde[Transaction] =
    Serde.forProduct3(Transaction.apply, t => (t.unsigned, t.data, t.witness))

  def from0(inputs: AVector[TxOutputPoint],
            outputs: AVector[TxOutput],
            publicKey: ED25519PublicKey,
            privateKey: ED25519PrivateKey): Transaction = {
    from(RawTransaction(inputs, outputs), publicKey, privateKey)
  }

  def from1(inputs: AVector[TxOutputPoint],
            outputs: AVector[TxOutput],
            witnesses: AVector[Witness]): Transaction = {
    Transaction(RawTransaction(inputs, outputs), ByteString.empty, witnesses)
  }

  def from(unsigned: RawTransaction,
           publicKey: ED25519PublicKey,
           privateKey: ED25519PrivateKey): Transaction = {
    // TODO: check the privateKey are valid to spend all the txinputs
    val witness = Script.p2pkhWitness(unsigned, publicKey, privateKey)
    Transaction(unsigned, ByteString.empty, AVector(witness))
  }

  def coinbase(publicKey: ED25519PublicKey, value: BigInt, data: ByteString): Transaction = {
    val pkScript = Script.p2pkhPub(publicKey)
    val txOutput = TxOutput(value, pkScript)
    val unsigned = RawTransaction(AVector.empty, AVector(txOutput))
    Transaction(unsigned, data, AVector.empty)
  }

  def genesis(balances: AVector[(ED25519PublicKey, BigInt)]): Transaction = {
    val outputs = balances.map {
      case (publicKey, value) =>
        val pkScript = Script.p2pkhPub(publicKey)
        TxOutput(value, pkScript)
    }
    val unsigned = RawTransaction(AVector.empty, outputs)
    Transaction(unsigned, ByteString.empty, AVector.empty)
  }
}
