package org.alephium.crypto

import java.nio.charset.Charset

import org.alephium.serde._
import org.alephium.util.Hex
import org.bouncycastle.crypto.Digest

trait HashOutput {
  def digest: Seq[Byte]

  override def toString: String = Hex.toHexString(digest)
}

trait Hash[T <: HashOutput] {
  def unsafeFrom(digest: Seq[Byte]): T

  def hashSize: Int

  def hash(input: Seq[Byte]): T = {
    provider.update(input.toArray, 0, input.length)
    val res = new Array[Byte](hashSize)
    provider.doFinal(res, 0)
    unsafeFrom(res.toSeq)
  }

  def hash(input: String): T = {
    hash(input.getBytes())
  }

  def hash(input: String, charset: Charset): T = {
    hash(input.getBytes(charset))
  }

  def provider: Digest

  // Scala sucks here: replacing lazy val with val could not work
  implicit lazy val serde: Serde[T] = {
    Serde.fixedSizeBytesSerde(hashSize, implicitly[Serde[Byte]]).xmap(unsafeFrom, _.digest)
  }
}
