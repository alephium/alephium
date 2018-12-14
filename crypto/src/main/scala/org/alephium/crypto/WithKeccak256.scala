package org.alephium.crypto

import akka.util.ByteString
import org.alephium.serde._
import org.alephium.util.Hex

class WithKeccak256[T: Serde] extends RandomBytes { self: T =>
  override lazy val bytes: ByteString = serialize[T](this)

  lazy val hash: Keccak256 = Keccak256.hash(bytes)

  def shortHash: String = Hex.toHexString(hash.bytes).take(8)
}
