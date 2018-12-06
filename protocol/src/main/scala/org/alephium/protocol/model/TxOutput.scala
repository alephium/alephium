package org.alephium.protocol.model

import org.alephium.crypto.ED25519PublicKey
import org.alephium.serde.Serde

case class TxOutput(value: BigInt, publicKey: ED25519PublicKey)

object TxOutput {
  implicit val serde: Serde[TxOutput] = Serde.forProduct2(apply, to => (to.value, to.publicKey))
}
