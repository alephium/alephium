package org.alephium.crypto

import org.alephium.serde._
import org.alephium.util.Hex

abstract class Keccak256Hash[T: Serde] { self: T =>
  def hash: Keccak256

  def shortHash: String = Hex.toHexString(hash.bytes).take(8)
}
