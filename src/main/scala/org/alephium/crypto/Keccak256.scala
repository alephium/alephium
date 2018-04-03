package org.alephium.crypto

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.KeccakDigest

case class Keccak256(bytes: Seq[Byte]) extends HashOutput

object Keccak256 extends Hash[Keccak256] {
  override val size: Int = 32

  private def apply(digest: Seq[Byte]): Keccak256 = {
    require(digest.length == size)
    new Keccak256(digest)
  }

  override def unsafeFrom(digest: Seq[Byte]): Keccak256 = apply(digest)

  override val provider: Digest = new KeccakDigest(size * 8)
}
