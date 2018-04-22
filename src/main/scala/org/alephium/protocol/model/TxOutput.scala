package org.alephium.protocol.model

import org.alephium.crypto.ED25519PublicKey
import org.alephium.serde.Serde
import org.alephium.util.UInt

case class TxOutput(value: UInt, publicKey: ED25519PublicKey)

object TxOutput {
  implicit val serde: Serde[TxOutput] = Serde.forProduct2(apply, to => (to.value, to.publicKey))
}
