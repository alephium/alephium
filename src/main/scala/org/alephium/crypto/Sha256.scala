package org.alephium.crypto

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA256Digest

case class Sha256(digest: Seq[Byte]) extends HashOutput

object Sha256 extends Hash[Sha256] {
  override val hashSize: Int = 32

  private def apply(digest: Seq[Byte]): Sha256 = {
    require(digest.length == hashSize)
    new Sha256(digest)
  }

  override def unsafeFrom(digest: Seq[Byte]): Sha256 = apply(digest)

  override val provider: Digest = new SHA256Digest()
}
