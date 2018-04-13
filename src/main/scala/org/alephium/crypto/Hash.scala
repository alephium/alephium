package org.alephium.crypto

import java.nio.charset.Charset

import akka.util.ByteString
import org.alephium.util.{Bytes, FixedSizeBytes}
import org.alephium.serde._
import org.bouncycastle.crypto.Digest

trait HashOutput extends Bytes

trait Hash[T <: HashOutput] extends FixedSizeBytes[T] {
  def hash(input: Seq[Byte]): T = {
    provider.update(input.toArray, 0, input.length)
    val res = new Array[Byte](size)
    provider.doFinal(res, 0)
    unsafeFrom(res.toSeq)
  }

  def hash(input: String): T = {
    hash(ByteString(input))
  }

  def hash(input: String, charset: Charset): T = {
    hash(ByteString(input, charset))
  }

//  def hash(input: ByteString): T = {
//    hash(input.toSeq)
//  }

  def hash[S](input: S)(implicit serializer: Serializer[S]): T = {
    hash(serializer.serialize(input))
  }

  def provider: Digest
}
