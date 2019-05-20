package org.alephium.protocol.model

import org.alephium.crypto.{Keccak256, Keccak256Hash}
import org.alephium.serde._

case class TxOutputPoint(txHash: Keccak256, outputIndex: Int) extends Keccak256Hash[TxOutputPoint] {
  def hash: Keccak256 = _getHash
}

object TxOutputPoint {
  implicit val serde: Serde[TxOutputPoint] =
    Serde.forProduct2(apply, ti => (ti.txHash, ti.outputIndex))
}
