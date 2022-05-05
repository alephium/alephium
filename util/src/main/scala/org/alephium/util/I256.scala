// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.util

import java.math.BigInteger

import akka.util.ByteString

class I256(val v: BigInteger) extends AnyVal with Ordered[I256] {
  import I256.validate

  @inline def isZero: Boolean = v.signum() == 0

  def isPositive: Boolean = v.signum() >= 0

  def addUnsafe(that: I256): I256 = {
    val underlying = this.v.add(that.v)
    assume(validate(underlying))
    I256.unsafe(underlying)
  }

  def add(that: I256): Option[I256] = {
    val underlying = this.v.add(that.v)
    if (validate(underlying)) Some(I256.unsafe(underlying)) else None
  }

  def subUnsafe(that: I256): I256 = {
    val underlying = this.v.subtract(that.v)
    assume(validate(underlying))
    I256.unsafe(underlying)
  }

  def sub(that: I256): Option[I256] = {
    val underlying = this.v.subtract(that.v)
    if (validate(underlying)) Some(I256.unsafe(underlying)) else None
  }

  def mulUnsafe(that: I256): I256 = {
    val underlying = this.v.multiply(that.v)
    assume(validate(underlying))
    I256.unsafe(underlying)
  }

  def mul(that: I256): Option[I256] = {
    val underlying = this.v.multiply(that.v)
    if (validate(underlying)) Some(I256.unsafe(underlying)) else None
  }

  def divUnsafe(that: I256): I256 = {
    assume(!(that.isZero || (that == I256.NegOne && this == I256.MinValue)))
    I256.unsafe(this.v.divide(that.v))
  }

  def div(that: I256): Option[I256] = {
    if (that.isZero || (that == I256.NegOne && this == I256.MinValue)) {
      None
    } else {
      Some(I256.unsafe(this.v.divide(that.v)))
    }
  }

  def modUnsafe(that: I256): I256 = {
    assume(!(that.isZero || (this.v == I256.lowerBound && that.v == I256.NegOne.toBigInt)))
    I256.unsafe(this.v.remainder(that.v))
  }

  def mod(that: I256): Option[I256] = {
    if (that.isZero || (this.v == I256.lowerBound && that.v == I256.NegOne.toBigInt)) {
      None
    } else {
      Some(I256.unsafe(this.v.remainder(that.v)))
    }
  }

  def compare(that: I256): Int = this.v.compareTo(that.v)

  def toByte: Option[Byte] = if (v.bitLength() <= 7) Some(v.intValue().toByte) else None
  def toInt: Option[Int]   = if (v.bitLength() <= 31) Some(v.intValue()) else None

  def toBigInt: BigInteger = v

  def toBytes: ByteString = {
    val tmp           = ByteString.fromArrayUnsafe(v.toByteArray)
    val paddingLength = 32 - tmp.length
    if (paddingLength > 0) {
      if (this >= I256.Zero) {
        ByteString.fromArrayUnsafe(Array.fill(paddingLength)(0)) ++ tmp
      } else {
        ByteString.fromArrayUnsafe(Array.fill(paddingLength)(0xff.toByte)) ++ tmp
      }
    } else {
      assume(paddingLength == 0)
      tmp
    }
  }

  override def toString: String = v.toString
}

object I256 {
  // scalastyle:off magic.number
  private[util] val upperBound = BigInteger.ONE.shiftLeft(255) // exclusive
  private[util] val lowerBound = upperBound.negate()           // inclusive
  // scalastyle:on magic.number

  def validate(value: BigInteger): Boolean = {
    value.bitLength() <= 255
  }

  def unsafe(value: BigInteger): I256 = {
    assume(validate(value))
    new I256(value)
  }

  def unsafe(bytes: ByteString): I256 = {
    unsafe(bytes.toArray)
  }

  def unsafe(bytes: Array[Byte]): I256 = {
    assume(bytes.length == 32)
    new I256(new BigInteger(bytes))
  }

  def from(bytes: ByteString): Option[I256] = {
    Option.when(bytes.length <= 32)(new I256(new BigInteger(bytes.toArray)))
  }

  def from(value: BigInteger): Option[I256] = {
    if (validate(value)) Some(new I256(value)) else None
  }

  def from(value: Int): I256 = {
    new I256(BigInteger.valueOf(value.toLong))
  }

  def from(value: Long): I256 = {
    new I256(BigInteger.valueOf(value))
  }

  def fromU256(value: U256): Option[I256] = {
    from(value.v)
  }

  val Zero: I256     = unsafe(BigInteger.ZERO)
  val One: I256      = unsafe(BigInteger.ONE)
  val Two: I256      = unsafe(BigInteger.valueOf(2))
  val NegOne: I256   = unsafe(BigInteger.ONE.negate())
  val MaxValue: I256 = unsafe(upperBound.subtract(BigInteger.ONE))
  val MinValue: I256 = unsafe(lowerBound)
}
