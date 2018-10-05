package org.alephium.protocol.model

import org.alephium.crypto._
import org.alephium.serde.Serde

case class BlockHeader(
    blockDeps: Seq[Keccak256],
    txsHash: Keccak256,
    timestamp: Long,
    target: BigInt,
    nonce: BigInt
)

object BlockHeader {

  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct5(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp, bh.target, bh.nonce))
}
