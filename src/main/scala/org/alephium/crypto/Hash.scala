package org.alephium.crypto

import org.alephium.serde.Serde
import scorex.crypto.hash.{Sha256 => _Sha256}

trait HashOutput {
  def digest: Array[Byte]
}

trait Hash[T <: HashOutput] {
  def hash(input: Array[Byte]): T

  def hashSize: Int

  def unsafeFrom(hash: Array[Byte]): T

  // Scala sucks here: replacing lazy val with value could not work
  implicit lazy val serde: Serde[T] = {
    Serde.fixedSizeBytesSerde(hashSize, implicitly[Serde[Byte]]).xmap(unsafeFrom, _.digest)
  }
}

object Hash {
  case class Sha256(digest: Array[Byte]) extends HashOutput

  object Sha256 extends Hash[Sha256] {
    override def hash(input: Array[Byte]): Sha256 = {
      val digest = _Sha256.hash(input)
      new Sha256(digest)
    }

    override val hashSize: Int = 32

    private def apply(digest: Array[Byte]): Sha256 = {
      require(digest.length == hashSize)
      new Sha256(digest)
    }

    override def unsafeFrom(hash: Array[Byte]): Sha256 = {
      require(hash.length == hashSize)
      new Sha256(hash)
    }
  }
}
