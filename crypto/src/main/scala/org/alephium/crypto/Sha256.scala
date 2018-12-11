package org.alephium.crypto

import akka.util.ByteString
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA256Digest

class Sha256(val bytes: ByteString) extends RandomBytes

object Sha256 extends Hash[Sha256] {
  override val size: Int = 32

  // TODO: optimize with queue of providers
  override def provider: Digest = new SHA256Digest()

  override def unsafeFrom(digest: ByteString): Sha256 = {
    assert(digest.length == size)
    new Sha256(digest)
  }
}
