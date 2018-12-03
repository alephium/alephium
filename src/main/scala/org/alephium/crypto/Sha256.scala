package org.alephium.crypto

import scorex.crypto.hash.{Sha256 => _Sha256}

case class Sha256(digest: Seq[Byte]) extends HashOutput

object Sha256 extends Hash[Sha256] {
  override val hashSize: Int = 32

  private def apply(digest: Seq[Byte]): Sha256 = {
    require(digest.length == hashSize)
    new Sha256(digest)
  }

  override def unsafeFrom(hash: Seq[Byte]): Sha256 = {
    require(hash.length == hashSize)
    new Sha256(hash)
  }

  override def hash(input: Seq[Byte]): Sha256 = {
    val digest = _Sha256.hash(input.toArray)
    apply(digest)
  }
}
