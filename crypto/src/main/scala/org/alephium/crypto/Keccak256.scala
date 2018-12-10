package org.alephium.crypto

import akka.util.ByteString
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.KeccakDigest

class Keccak256(val bytes: ByteString) extends HashOutput

object Keccak256 extends Hash[Keccak256] {
  override val size: Int = 32

  // TODO: optimize with queue of providers
  override def provider: Digest = new KeccakDigest(size * 8)

  override def unsafeFrom(digest: ByteString): Keccak256 = {
    assert(digest.length == size)
    new Keccak256(digest)
  }
}
