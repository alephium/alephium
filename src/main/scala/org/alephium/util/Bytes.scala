package org.alephium.util

import org.alephium.serde._

trait Bytes {
  def bytes: Seq[Byte]

  override def toString: String = Hex.toHexString(bytes)
}

trait FixedSizeBytes[T <: Bytes] {
  def size: Int

  def unsafeFrom(data: Seq[Byte]): T

  // Scala sucks here: replacing lazy val with val could not work
  implicit lazy val serde: Serde[T] =
    Serde.fixedSizeBytesSerde(size, implicitly[Serde[Byte]]).xmap(unsafeFrom, _.bytes)
}
