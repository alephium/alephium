package org.alephium.protocol

import org.alephium.protocol.ALFPublicKey

package object model {
  val cliqueIdLength: Int = ALFPublicKey.length

  type TokenId = Hash
}
