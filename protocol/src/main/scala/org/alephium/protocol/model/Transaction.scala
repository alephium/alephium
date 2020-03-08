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
    raw: RawTransaction,
    data: ByteString,
    witnesses: AVector[Witness]
) extends Keccak256Hash[Transaction] {
  override val hash: Keccak256 = raw.hash

  def fromGroup(implicit config: GroupConfig): GroupIndex  = raw.inputs.head.fromGroup
  def toGroup(implicit config: GroupConfig): GroupIndex    = raw.outputs.head.toGroup
  def chainIndex(implicit config: GroupConfig): ChainIndex = ChainIndex(fromGroup, toGroup)
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

  def coinbase(publicKey: ED25519PublicKey, data: ByteString): Transaction = {
    val pkScript = PubScript.p2pkh(publicKey)
    val txOutput = TxOutput(ALF.CoinBaseValue, pkScript)
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

  def simpleTransfer(inputs: AVector[TxOutputPoint],
                     inputSum: BigInt,
                     from: ED25519PublicKey,
                     to: ED25519PublicKey,
                     value: BigInt,
                     privateKey: ED25519PrivateKey): Transaction = {
    assume(inputSum >= value)
    val fromPubScript = PubScript.p2pkh(from)
    val toPubScript   = PubScript.p2pkh(to)
    val toOutput      = TxOutput(value, toPubScript)
    val fromOutput    = TxOutput(inputSum - value, fromPubScript)
    val outputs       = if (inputSum - value > 0) AVector(toOutput, fromOutput) else AVector(toOutput)
    val raw           = RawTransaction(inputs, outputs)
    val witness       = Witness.p2pkh(raw, from, privateKey)
    Transaction(raw, ByteString.empty, AVector.fill(inputs.length)(witness))
  }
}
