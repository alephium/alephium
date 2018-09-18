package org.alephium.crypto

import java.nio.charset.Charset
import java.security.SecureRandom

import akka.util.ByteString
import org.alephium.serde._
import org.bouncycastle.crypto.Digest

trait HashOutput extends Bytes {
  // scalastyle:off magic.number
  override def hashCode(): Int = {
    (bytes(28) & 0xFF) << 24 | (bytes(29) & 0xFF) << 16 | (bytes(30) & 0xFF) << 8 | (bytes(31) & 0xFF)
  }
  // scalastyle:on magic.number

  override def equals(obj: Any): Boolean = obj match {
    case that: HashOutput => bytes == that.bytes
    case _                => false
  }
}

trait Hash[T <: HashOutput] extends FixedSizeBytes[T] {
  def provider: Digest

  def hash(input: Seq[Byte]): T = {
    val _provider = provider
    _provider.update(input.toArray, 0, input.length)
    val res = new Array[Byte](size)
    _provider.doFinal(res, 0)
    unsafeFrom(ByteString(res))
  }

  def hash(input: String): T = {
    hash(ByteString(input))
  }

  def hash(input: String, charset: Charset): T = {
    hash(ByteString(input, charset))
  }

  def hash[S](input: S)(implicit serializer: Serializer[S]): T = {
    hash(serializer.serialize(input))
  }

  def random: T = hash(ByteString(SecureRandom.getInstanceStrong.nextLong))
}
