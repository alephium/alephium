package org.alephium.protocol.model

import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.script.{PayTo, PubScript}
import org.alephium.serde._

final case class TxOutput(value: BigInt, pubScript: PubScript) {
  def shortKey: Int = pubScript.shortKey

  def toGroup(implicit config: GroupConfig): GroupIndex = pubScript.groupIndex
}

object TxOutput {
  implicit val serde: Serde[TxOutput] = Serde.forProduct2(apply, to => (to.value, to.pubScript))

  def build(payTo: PayTo, value: BigInt, publicKey: ED25519PublicKey): TxOutput = {
    val pubScript = PubScript.build(payTo, publicKey)
    TxOutput(value, pubScript)
  }

  // TODO: use proper op_code when it's ready
  def burn(value: BigInt): TxOutput = {
    TxOutput(value, PubScript.empty)
  }
}
