package org.alephium.crypto

import org.alephium.serde._

abstract class Keccak256Hash[T: Serde] { self: T =>
  def hash: Keccak256

  protected def _getHash: Keccak256 = Keccak256.hash(serialize[T](this))

  def shortHex: String = hash.shortHex
}
