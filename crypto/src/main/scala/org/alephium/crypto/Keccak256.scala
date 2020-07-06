package org.alephium.crypto

import akka.util.ByteString
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.KeccakDigest

import org.alephium.serde.RandomBytes

class Keccak256(val bytes: ByteString) extends RandomBytes {
  def toByte32: Byte32 = Byte32.unsafe(bytes)
}

object Keccak256 extends HashSchema[Keccak256](HashSchema.unsafeKeccak256, _.bytes) {
  override def length: Int = 32

  // TODO: optimize with queue of providers
  override def provider: Digest = new KeccakDigest(length * 8)
}
