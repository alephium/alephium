package org.alephium.protocol

import org.alephium.serde.{serialize, Serde}

abstract class HashSerde[T: Serde] { self: T =>
  def hash: Hash

  protected def _getHash: Hash = Hash.hash(serialize[T](this))

  def shortHex: String = hash.shortHex
}
