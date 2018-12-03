package org.alephium.crypto

import java.nio.charset.Charset

import org.alephium.util.{Bytes, FixedSizeBytes}
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
    hash(input.getBytes())
  }

  def hash(input: String, charset: Charset): T = {
    hash(input.getBytes(charset))
  }

  def provider: Digest
}
