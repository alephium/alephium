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

// scalastyle:off number.of.methods

class U256(val v: BigInteger) extends AnyVal with Ordered[U256] {
  import U256.validate

  def isZero: Boolean = v.signum() == 0

  def nonZero: Boolean = v.signum() != 0

  def addUnsafe(that: U256): U256 = {
    val underlying = this.v.add(that.v)
    assume(validate(underlying))
    U256.unsafe(underlying)
  }

  def addOneUnsafe(): U256 = addUnsafe(U256.One)

  def add(that: U256): Option[U256] = {
    val underlying = this.v.add(that.v)
    if (validate(underlying)) Some(U256.unsafe(underlying)) else None
  }

  def modAdd(that: U256): U256 = {
    U256.boundNonNegative(this.v.add(that.v))
  }

  def subUnsafe(that: U256): U256 = {
    val underlying = this.v.subtract(that.v)
    assume(validate(underlying))
    U256.unsafe(underlying)
  }

  def subOneUnsafe(): U256 = subUnsafe(U256.One)

  def sub(that: U256): Option[U256] = {
    val underlying = this.v.subtract(that.v)
    if (validate(underlying)) Some(U256.unsafe(underlying)) else None
  }

  def modSub(that: U256): U256 = {
    U256.boundSub(this.v.subtract(that.v))
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

  def modMul(that: U256): U256 = {
    U256.boundNonNegative(this.v.multiply(that.v))
  }

  def divUnsafe(that: U256): U256 = {
    assume(!that.isZero)
    U256.unsafe(this.v.divide(that.v))
  }

  def div(that: U256): Option[U256] = {
    if (that.isZero) {
      None
    } else {
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

  def bitAnd(that: U256): U256 = {
    U256.unsafe(this.v.and(that.v))
  }

  def bitOr(that: U256): U256 = {
    U256.unsafe(this.v.or(that.v))
  }

  def xor(that: U256): U256 = {
    U256.unsafe(this.v.xor(that.v))
  }

  def shl(n: U256): U256 = {
    try {
      val nInt = n.toBigInt.intValueExact()
      if (nInt >= 0 && nInt < 256) {
        U256.boundNonNegative(this.v.shiftLeft(nInt))
      } else {
        U256.Zero
      }
    } catch {
      case _: ArithmeticException => U256.Zero
    }
  }

  def shr(n: U256): U256 = {
    try {
      val nInt = n.toBigInt.intValueExact()
      if (nInt >= 0 && nInt < 256) {
        U256.unsafe(this.v.shiftRight(nInt))
      } else {
        U256.Zero
      }
    } catch {
      case _: ArithmeticException => U256.Zero
    }
  }

  def compare(that: U256): Int = this.v.compareTo(that.v)

  def toByte: Option[Byte] = if (v.bitLength() <= 7) Some(v.intValue().toByte) else None

  def toInt: Option[Int] = try {
    Some(toIntUnsafe)
  } catch {
    case _: ArithmeticException => None
  }

  def toIntUnsafe: Int = v.intValueExact()

  def toLong: Option[Long] = try {
    Some(v.longValueExact())
  } catch {
    case _: ArithmeticException => None
  }

  def toBigInt: BigInteger = v

  def toBytes: ByteString = _toBytes(32)

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  private def _toBytes(size: Int): ByteString = {
    val tmp           = ByteString.fromArrayUnsafe(v.toByteArray)
    val paddingLength = size - tmp.length
    if (paddingLength < 0) {
      tmp.tail
    } else if (paddingLength > 0) {
      ByteString.fromArrayUnsafe(Array.fill(paddingLength)(0)) ++ tmp
    } else {
      tmp
    }
  }

  def toFixedSizeBytes(size: Int): Option[ByteString] =
    Option.when(v.bitLength() <= size * 8)(_toBytes(size))

  override def toString: String = v.toString()
}

object U256 {
  private[util] val upperBound = BigInteger.ONE.shiftLeft(256)

  def boundNonNegative(value: BigInteger): U256 = {
    assume(value.signum() >= 0)
    val raw        = value.toByteArray
    val boundedRaw = if (raw.length > 32) raw.takeRight(32) else raw
    U256.unsafe(new BigInteger(1, boundedRaw))
  }

  def boundSub(value: BigInteger): U256 = {
    if (value.signum() < 0) {
      U256.unsafe(value.add(upperBound))
    } else {
      U256.unsafe(value)
    }
  }

  def validate(value: BigInteger): Boolean = {
    Number.nonNegative(value) && value.bitLength() <= 256
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

  def unsafe(bytes: Array[Byte]): U256 = {
    assume(bytes.length == 32)
    new U256(new BigInteger(1, bytes))
  }

  def unsafe(bytes: ByteString): U256 = {
    unsafe(bytes.toArray)
  }

  def from(bytes: ByteString): Option[U256] = {
    from(new BigInteger(1, bytes.toArray))
  }

  def from(value: BigInteger): Option[U256] = {
    if (validate(value)) Some(new U256(value)) else None
  }

  def fromLong(value: Long): Option[U256] = {
    if (value >= 0) Some(unsafe(value)) else None
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

  val HalfMaxValue: U256 = MaxValue.divUnsafe(U256.Two)

  val Million: U256 = unsafe(Number.million)
  val Billion: U256 = unsafe(Number.billion)
}
