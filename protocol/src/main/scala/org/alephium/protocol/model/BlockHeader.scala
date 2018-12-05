package org.alephium.protocol.model

import org.alephium.crypto._
import org.alephium.serde.Serde
import org.alephium.util.UInt

case class BlockHeader(
    blockDeps: Seq[Keccak256],
    txsHash: Keccak256,
    timestamp: Long,
    nonce: UInt
)

object BlockHeader {
  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct4(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp, bh.nonce))
}
