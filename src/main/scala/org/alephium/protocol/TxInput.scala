package org.alephium.protocol

import org.alephium.crypto.Keccak256
import org.alephium.serde.Serde

case class TxInput(txHash: Keccak256, outputIndex: Int)

object TxInput {
  implicit val serde: Serde[TxInput] = Serde.forProduct2(apply, ti => (ti.txHash, ti.outputIndex))
}
