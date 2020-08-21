package org.alephium.protocol

import org.alephium.crypto.ALFPublicKey

package object model {
  val cliqueIdLength: Int = ALFPublicKey.length

  type TokenId = Hash
}
