package org.alephium.protocol

import org.alephium.crypto._
import org.alephium.serde.Serde

case class BlockHeader(
    blockDeps: Seq[Keccak256],
    txsHash: Keccak256,
    timestamp: Long
//    difficulty: Int,
//    nonce: Int,
)

object BlockHeader {
  implicit val serde: Serde[BlockHeader] = Serde.forProduct3(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp))
}
