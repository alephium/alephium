package org.alephium.protocol

import org.alephium.crypto._

case class BlockHeader(
    blockDeps: Seq[Keccak256],
    txsHash: Keccak256,
    timestamp: Long,
//    difficulty: Int,
//    nonce: Int,
)
