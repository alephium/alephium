package org.alephium.primitive

case class Transaction(inputs: Seq[TxInput], outputs: Seq[TxOutput], signatures: Seq[Byte])
