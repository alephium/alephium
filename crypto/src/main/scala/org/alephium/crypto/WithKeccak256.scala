package org.alephium.crypto

import org.alephium.serde._
import org.alephium.util.Hex

class WithKeccak256[T: Serde] { self: T =>
  lazy val hash: Keccak256 = Keccak256.hash(serialize[T](this))

  def shortHash: String = Hex.toHexString(hash.bytes).take(8)
}
