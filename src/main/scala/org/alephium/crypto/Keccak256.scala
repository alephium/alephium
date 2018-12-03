package org.alephium.crypto

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.KeccakDigest

case class Keccak256(digest: Seq[Byte]) extends HashOutput

object Keccak256 extends Hash[Keccak256] {
  override def hashSize: Int = 32

  private def apply(digest: Seq[Byte]): Keccak256 = {
    require(digest.length == hashSize)
    new Keccak256(digest)
  }

  override def unsafeFrom(digest: Seq[Byte]): Keccak256 = apply(digest)

  override val provider: Digest = new KeccakDigest(hashSize * 8)
}
