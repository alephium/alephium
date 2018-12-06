package org.alephium.protocol.model

import java.math.BigInteger

import org.alephium.crypto._
import org.alephium.serde.Serde

case class BlockHeader(
    blockDeps: Seq[Keccak256],
    txsHash: Keccak256,
    timestamp: Long,
    nonce: BigInteger
)

object BlockHeader {
  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct4(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp, bh.nonce))
}
