package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.ALF.{Hash, HashSerde}
import org.alephium.protocol.script.{PayTo, PubScript}
import org.alephium.serde._
import org.alephium.util.AVector

final case class UnsignedTransaction(inputs: AVector[TxOutputPoint],
                                     outputs: AVector[TxOutput],
                                     data: ByteString)
    extends HashSerde[UnsignedTransaction] {
  override val hash: Hash = _getHash

}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] =
    Serde.forProduct3(UnsignedTransaction(_, _, _), t => (t.inputs, t.outputs, t.data))

  def simpleTransfer(inputs: AVector[TxOutputPoint],
                     inputSum: BigInt,
                     from: ED25519PublicKey,
                     fromPayTo: PayTo,
                     to: ED25519PublicKey,
                     toPayTo: PayTo,
                     value: BigInt): UnsignedTransaction = {
    assume(inputSum >= value)
    val fromPubScript = PubScript.build(fromPayTo, from)
    val toPubScript   = PubScript.build(toPayTo, to)
    val toOutput      = TxOutput(value, toPubScript)
    val fromOutput    = TxOutput(inputSum - value, fromPubScript)
    val outputs       = if (inputSum - value > 0) AVector(toOutput, fromOutput) else AVector(toOutput)
    UnsignedTransaction(inputs, outputs, ByteString.empty)
  }
}
