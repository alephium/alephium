package org.alephium.protocol.model

import org.alephium.crypto.Keccak256
import org.alephium.serde._

case class TxOutputPoint(shortKey: Int, txHash: Keccak256, outputIndex: Int)

object TxOutputPoint {
  // Note that the serialization has to put mainKey in the first 32 bytes for the sake of trie indexing
  implicit val serde: Serde[TxOutputPoint] =
    Serde.forProduct3(apply, ti => (ti.shortKey, ti.txHash, ti.outputIndex))
}
