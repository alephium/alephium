package org.alephium.protocol.model

import org.alephium.crypto.Keccak256
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._

case class TxOutputPoint(shortKey: Int, txHash: Keccak256, outputIndex: Int) {
  def fromGroup(implicit config: GroupConfig): GroupIndex = GroupIndex.fromShortKey(shortKey)
}

object TxOutputPoint {
  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputPoint = {
    assume(outputIndex >= 0 && outputIndex < transaction.raw.outputs.length)
    val output = transaction.raw.outputs(outputIndex)
    TxOutputPoint(output.shortKey, transaction.hash, outputIndex)
  }

  // Note that the serialization has to put mainKey in the first 32 bytes for the sake of trie indexing
  implicit val serde: Serde[TxOutputPoint] =
    Serde.forProduct3(apply, ti => (ti.shortKey, ti.txHash, ti.outputIndex))
}
