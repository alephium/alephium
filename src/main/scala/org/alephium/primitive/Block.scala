package org.alephium.primitive

case class Block(blockHeader: BlockHeader, transactions: Seq[Transaction])
