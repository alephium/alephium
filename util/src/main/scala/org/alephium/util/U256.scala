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

class U256(val v: BigInteger) extends AnyVal with Ordered[U256] {
  import U256.validate

  def isZero: Boolean = v.signum() == 0

  def nonZero: Boolean = v.signum() != 0

  def addUnsafe(that: U256): U256 = {
    val underlying = this.v.add(that.v)
    assume(validate(underlying))
    U256.unsafe(underlying)
  }

  def add(that: U256): Option[U256] = {
    val underlying = this.v.add(that.v)
    if (validate(underlying)) Some(U256.unsafe(underlying)) else None
  }

  def subUnsafe(that: U256): U256 = {
    val underlying = this.v.subtract(that.v)
    assume(validate(underlying))
    U256.unsafe(underlying)
  }

  def sub(that: U256): Option[U256] = {
    val underlying = this.v.subtract(that.v)
    if (validate(underlying)) Some(U256.unsafe(underlying)) else None
  }

  def mulUnsafe(that: U256): U256 = {
    val underlying = this.v.multiply(that.v)
    assume(validate(underlying))
    U256.unsafe(underlying)
  }

  def mul(that: U256): Option[U256] = {
    val underlying = this.v.multiply(that.v)
    if (validate(underlying)) Some(U256.unsafe(underlying)) else None
  }

  def divUnsafe(that: U256): U256 = {
    assume(!that.isZero)
    U256.unsafe(this.v.divide(that.v))
  }

  def div(that: U256): Option[U256] = {
    if (that.isZero) None
    else {
      Some(U256.unsafe(this.v.divide(that.v)))
    }
  }

  def modUnsafe(that: U256): U256 = {
    assume(!that.isZero)
    U256.unsafe(this.v.remainder(that.v))
  }

  def mod(that: U256): Option[U256] = {
    if (that.isZero) None else Some(U256.unsafe(this.v.remainder(that.v)))
  }

  def compare(that: U256): Int = this.v.compareTo(that.v)

  def toByte: Option[Byte] = if (v.bitLength() <= 7) Some(v.intValue().toByte) else None

  def toBigInt: BigInteger = v

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def toBytes: ByteString = {
    val tmp           = ByteString.fromArrayUnsafe(v.toByteArray)
    val paddingLength = 32 - tmp.length
    if (paddingLength < 0) tmp.tail
    else if (paddingLength > 0) {
      ByteString.fromArrayUnsafe(Array.fill(paddingLength)(0)) ++ tmp
    } else tmp
  }
}

object U256 {
  private[util] val upperBound = BigInteger.ONE.shiftLeft(256)

  def validate(value: BigInteger): Boolean = {
    Numeric.nonNegative(value) && value.bitLength() <= 256
  }

  def unsafe(value: BigInteger): U256 = {
    assume(validate(value))
    new U256(value)
  }

  def unsafe(value: Int): U256 = {
    unsafe(value.toLong)
  }

  def unsafe(value: Long): U256 = {
    assume(value >= 0)
    new U256(BigInteger.valueOf(value))
  }

  def unsafe(bytes: ByteString): U256 = {
    assume(bytes.length == 32)
    new U256(new BigInteger(1, bytes.toArray))
  }

  def from(value: BigInteger): Option[U256] = {
    if (validate(value)) Some(new U256(value)) else None
  }

  def fromI64(value: I64): Option[U256] = {
    if (value.isPositive) Some(unsafe(value.v)) else None
  }

  def fromU64(value: U64): U256 = {
    unsafe(value.v)
  }

  def fromI256(value: I256): Option[U256] = {
    if (value.isPositive) Some(unsafe(value.v)) else None
  }

  val Zero: U256     = unsafe(BigInteger.ZERO)
  val One: U256      = unsafe(BigInteger.ONE)
  val Two: U256      = unsafe(BigInteger.valueOf(2))
  val Ten: U256      = unsafe(BigInteger.TEN)
  val MaxValue: U256 = unsafe(upperBound.subtract(BigInteger.ONE))
  val MinValue: U256 = Zero

  // scalastyle:off magic.number
  val Million: U256 = unsafe(1000000)
  val Billion: U256 = unsafe(1000000000)
  // scalastyle:on magic.number
}
