package org.alephium.util

import org.alephium.serde.Serde

class UInt(val underlying: Int) extends AnyVal with Ordered[UInt] {
  override def compare(that: UInt): Int = Integer.compareUnsigned(underlying, that.underlying)

  def plus(that: UInt): UInt = new UInt(underlying + that.underlying)

  def minus(that: UInt): UInt = new UInt(underlying - that.underlying)

  override def toString: String = underlying.toString
}

object UInt {
  val zero: UInt = new UInt(0)

  val one: UInt = new UInt(1)

  def apply(v: Int): UInt = new UInt(v)

  def fromString(s: String): UInt = new UInt(Integer.parseUnsignedInt(s))

  implicit val serde: Serde[UInt] = Serde.forProduct1(apply, _.underlying)
}
