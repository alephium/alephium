package org.alephium.crypto

import java.nio.charset.Charset

import org.alephium.serde._
import org.alephium.util.Hex
import scorex.crypto.hash.{Sha256 => _Sha256}

trait HashOutput {
  def digest: Seq[Byte]

  override def toString: String = Hex.toHexString(digest)
}

trait Hash[T <: HashOutput] {
  def unsafeFrom(hash: Seq[Byte]): T

  def hashSize: Int

  def hash(input: Seq[Byte]): T

  def hash(input: String): T = {
    hash(input.getBytes())
  }

  def hash(input: String, charset: Charset): T = {
    hash(input.getBytes(charset))
  }

  // Scala sucks here: replacing lazy val with val could not work
  implicit lazy val serde: Serde[T] = {
    Serde.fixedSizeBytesSerde(hashSize, implicitly[Serde[Byte]]).xmap(unsafeFrom, _.digest)
  }
}

final case class Sha256(digest: Seq[Byte]) extends HashOutput

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
