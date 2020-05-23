package org.alephium.protocol.model

import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.ALF.{Hash, HashSerde}
import org.alephium.protocol.script.{PayTo, PriScript, PubScript, Script}
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

final case class UnsignedTransaction(script: Option[Script],
                                     inputs: AVector[TxInput],
                                     fixedOutputs: AVector[TxOutput])
    extends HashSerde[UnsignedTransaction] {
  override lazy val hash: Hash = _getHash
}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] =
    Serde.forProduct3(UnsignedTransaction(_, _, _), t => (t.script, t.inputs, t.fixedOutputs))

  def transferAlf(inputs: AVector[TxOutputRef],
                  inputSum: U64,
                  from: ED25519PublicKey,
                  fromPayTo: PayTo,
                  to: ED25519PublicKey,
                  toPayTo: PayTo,
                  amount: U64): UnsignedTransaction = {
    assume(inputSum >= amount)
    val remainder = inputSum.subUnsafe(amount)

    val fromPubScript = PubScript.build(fromPayTo, from)
    val toPubScript   = PubScript.build(toPayTo, to)
    val toOutput      = AlfOutput.build(amount, toPubScript)
    val fromOutput    = AlfOutput.build(remainder, fromPubScript)
    val unlockScript  = PriScript.build(fromPayTo, from)

    val outputs =
      if (remainder > U64.Zero) AVector[TxOutput](toOutput, fromOutput)
      else AVector[TxOutput](toOutput)
    UnsignedTransaction(None, inputs.map(TxInput(_, unlockScript)), outputs)
  }
}
