package org.alephium.protocol

import org.alephium.crypto.Sha256

case class TxInput(prevOutput: Sha256, signature: Seq[Byte])
