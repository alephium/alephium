package org.alephium.protocol

import org.alephium.protocol.PublicKey
import org.alephium.util.Bytes.byteStringOrdering

package object model {
  val cliqueIdLength: Int = PublicKey.length

  type TokenId    = Hash
  type ContractId = Hash

  implicit val tokenIdOrder: Ordering[TokenId] = Ordering.by(_.bytes)
}
