package org.alephium.crypto

import org.alephium.serde._
import org.alephium.util.Hex

abstract class WithKeccak256[T: Serde] { self: T =>
  def hash: Keccak256

  def shortHash: String = Hex.toHexString(hash.bytes).take(8)
}
