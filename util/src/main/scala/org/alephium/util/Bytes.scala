package org.alephium.util

import akka.util.ByteString
import org.alephium.serde._

trait Bytes {
  def bytes: ByteString

  override def toString: String = Hex.toHexString(bytes)
}

trait FixedSizeBytes[T <: Bytes] {
  def size: Int

  def unsafeFrom(data: ByteString): T

  lazy val zero: T = unsafeFrom(ByteString(Array.fill[Byte](size)(0)))

  // Scala sucks here: replacing lazy val with val could not work
  implicit lazy val serde: Serde[T] =
    Serde
      .fixedSizeBytesSerde(size, implicitly[Serde[Byte]])
      .xmap(bytes => unsafeFrom(ByteString(bytes.toArray)), _.bytes)
}
