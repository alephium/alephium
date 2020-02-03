package org.alephium.protocol.model

import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.script.PubScript
import org.alephium.serde._

case class TxOutput(value: BigInt, pubScript: PubScript) {
  def shortKey: Int = pubScript.shortKey
}

object TxOutput {
  implicit val serde: Serde[TxOutput] = Serde.forProduct2(apply, to => (to.value, to.pubScript))

  def p2pkh(value: BigInt, publicKey: ED25519PublicKey): TxOutput = {
    val pubScript = PubScript.p2pkh(publicKey)
    TxOutput(value, pubScript)
  }

  // TODO: use proper op_code when it's ready
  def burn(value: BigInt): TxOutput = {
    TxOutput(value, PubScript.empty)
  }
}
