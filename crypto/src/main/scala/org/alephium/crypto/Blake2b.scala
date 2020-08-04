package org.alephium.crypto

import akka.util.ByteString
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.Blake2bDigest

import org.alephium.serde.RandomBytes

class Blake2b(val bytes: ByteString) extends RandomBytes {
  def toByte32: Byte32 = Byte32.unsafe(bytes)
}

object Blake2b extends HashSchema[Blake2b](HashSchema.unsafeBlake2b, _.bytes) {
  override def length: Int = 32

  override def provider: Digest = new Blake2bDigest(length * 8)
}
