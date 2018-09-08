package org.alephium.serde

import akka.util.ByteString
import org.alephium.util.Hex

trait Bytes {
  def bytes: ByteString

  override def toString: String = Hex.toHexString(bytes)

  override def hashCode(): Int = bytes.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case that: Bytes => bytes.equals(that.bytes)
    case _           => false
  }
}

trait FixedSizeBytes[T <: Bytes] {
  def size: Int

  def unsafeFrom(data: ByteString): T

  lazy val zero: T = unsafeFrom(ByteString(Array.fill[Byte](size)(0)))

  // Scala sucks here: replacing lazy val with val could not work
  implicit lazy val serde: Serde[T] =
    Serde
      .fixedSizeBytesSerde(size, Serde[Byte])
      .xmap(bytes => unsafeFrom(ByteString(bytes.toArray)), _.bytes)
}
