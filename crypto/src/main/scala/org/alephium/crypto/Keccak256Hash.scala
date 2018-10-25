package org.alephium.crypto

import org.alephium.serde._

abstract class Keccak256Hash[T: Serde] { self: T =>
  def hash: Keccak256

  def shortHex: String = hash.shortHex
}
