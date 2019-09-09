package org.alephium.crypto

import java.nio.charset.Charset

import scala.reflect.runtime.universe.TypeTag

import akka.util.ByteString
import org.bouncycastle.crypto.Digest

import org.alephium.serde._

abstract class HashCompanion[T: TypeTag](unsafeFrom: ByteString => T, toBytes: T => ByteString)
    extends RandomBytes.Companion[T](unsafeFrom, toBytes) {
  def provider: Digest

  def hash(input: Seq[Byte]): T = {
    val _provider = provider
    _provider.update(input.toArray, 0, input.length)
    val res = new Array[Byte](length)
    _provider.doFinal(res, 0)
    unsafeFrom(ByteString.fromArrayUnsafe(res))
  }

  def hash(input: String): T = {
    hash(ByteString.fromString(input))
  }

  def hash(input: String, charset: Charset): T = {
    hash(ByteString.fromString(input, charset))
  }

  def hash[S](input: S)(implicit serializer: Serializer[S]): T = {
    hash(serializer.serialize(input))
  }

  def random: T = {
    val input = Array.fill[Byte](8)(0)
    RandomBytes.source.nextBytes(input)
    hash(input.toSeq)
  }
}
