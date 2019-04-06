package org.alephium.protocol.model

import org.alephium.crypto.Keccak256
import org.alephium.serde.Serde

case class TxOutputPoint(txHash: Keccak256, outputIndex: Int)

object TxOutputPoint {
  implicit val serde: Serde[TxOutputPoint] =
    Serde.forProduct2(apply, ti => (ti.txHash, ti.outputIndex))
}
