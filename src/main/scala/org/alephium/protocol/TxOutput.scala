package org.alephium.protocol

import org.alephium.crypto.ED25519PublicKey

case class TxOutput(value: Int, publicKey: ED25519PublicKey)
