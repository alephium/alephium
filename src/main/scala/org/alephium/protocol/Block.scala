package org.alephium.protocol

case class Block(blockHeader: BlockHeader, transactions: Seq[Transaction])
