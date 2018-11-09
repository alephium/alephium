package org.alephium.protocol

import org.alephium.crypto.ED25519PublicKey

package object model {
  val peerIdLength: Int = ED25519PublicKey.length
}
