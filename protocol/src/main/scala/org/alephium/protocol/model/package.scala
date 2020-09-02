package org.alephium.protocol

import org.alephium.protocol.PublicKey

package object model {
  val cliqueIdLength: Int = PublicKey.length

  type TokenId = Hash
}
