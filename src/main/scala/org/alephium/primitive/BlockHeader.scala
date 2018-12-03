package org.alephium.primitive

import org.alephium.crypto._

case class BlockHeader(
    prevBlock: Sha256,
    txsHash: Sha256,
    timestamp: Int,
    difficulty: Int,
    transactionCnt: Int,
    nonce: Int
)
