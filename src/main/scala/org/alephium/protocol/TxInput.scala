package org.alephium.protocol

import org.alephium.crypto.Keccak256

case class TxInput(txHash: Keccak256, outputIndex: Int)
