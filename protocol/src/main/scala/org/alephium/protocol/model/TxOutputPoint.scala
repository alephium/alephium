package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.crypto.Keccak256
import org.alephium.serde._

case class TxOutputPoint(shortKey: Long, txHash: Keccak256, outputIndex: Int) {
  def trieKey: ByteString = serialize(this)
}

object TxOutputPoint {
  // Note that the serialization has to put mainKey in the first 32 bytes for the sake of trie indexing
  implicit val serde: Serde[TxOutputPoint] =
    Serde.forProduct3(apply, ti => (ti.shortKey, ti.txHash, ti.outputIndex))
}
