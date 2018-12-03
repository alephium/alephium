package org.alephium.protocol

case class Transaction(inputs: Seq[TxInput], outputs: Seq[TxOutput], signatures: Seq[Byte])
