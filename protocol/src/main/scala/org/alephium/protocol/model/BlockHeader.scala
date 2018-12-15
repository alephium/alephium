package org.alephium.protocol.model

import org.alephium.crypto._
import org.alephium.serde.Serde
import org.alephium.util.AVector

case class BlockHeader(
    blockDeps: AVector[Keccak256],
    txsHash: Keccak256,
    timestamp: Long,
    target: BigInt,
    nonce: BigInt
) extends WithKeccak256[BlockHeader]

object BlockHeader {

  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct5(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp, bh.target, bh.nonce))
}
