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

class U64(val v: Long) extends AnyVal with Ordered[U64] {
  import java.lang.{Long => JLong}

  @inline def isZero: Boolean = v == 0

  def addUnsafe(that: U64): U64 = {
    val underlying = this.v + that.v
    assume(U64.checkAdd(this, underlying))
    U64.unsafe(underlying)
  }

  def add(that: U64): Option[U64] = {
    val underlying = this.v + that.v
    if (U64.checkAdd(this, underlying)) Some(U64.unsafe(underlying)) else None
  }

  def addOneUnsafe(): U64 = addUnsafe(U64.One)

  def addOne(): Option[U64] = add(U64.One)

  def subUnsafe(that: U64): U64 = {
    assume(U64.checkSub(this, that))
    U64.unsafe(this.v - that.v)
  }

  def sub(that: U64): Option[U64] = {
    if (U64.checkSub(this, that)) {
      Some(U64.unsafe(this.v - that.v))
    } else None
  }

  def subOneUnsafe(): U64 = subUnsafe(U64.One)

  def subOne(): Option[U64] = sub(U64.One)

  def mulUnsafe(that: U64): U64 = {
    if (this.v == 0) U64.Zero
    else {
      val underlying = this.v * that.v
      assume(U64.checkMul(this, that, underlying))
      U64.unsafe(underlying)
    }
  }

  def mul(that: U64): Option[U64] = {
    if (this.v == 0) Some(U64.Zero)
    else {
      val underlying = this.v * that.v
      if (U64.checkMul(this, that, underlying)) {
        Some(U64.unsafe(underlying))
      } else None
    }
  }

  def divUnsafe(that: U64): U64 = {
    assume(!that.isZero)
    U64.unsafe(JLong.divideUnsigned(this.v, that.v))
  }

  def div(that: U64): Option[U64] = {
    if (that.isZero) None else Some(U64.unsafe(JLong.divideUnsigned(this.v, that.v)))
  }

  def modUnsafe(that: U64): U64 = {
    assume(!that.isZero)
    U64.unsafe(JLong.remainderUnsigned(this.v, that.v))
  }

  def mod(that: U64): Option[U64] = {
    if (that.isZero) None else Some(U64.unsafe(JLong.remainderUnsigned(this.v, that.v)))
  }

  def compare(that: U64): Int = JLong.compareUnsigned(this.v, that.v)

  def min(that: U64): U64 = if (this > that) that else this
  def max(that: U64): U64 = if (this < that) that else this

  def toBigInt: BigInteger = {
    val bi = BigInteger.valueOf(v)
    if (v < 0) bi.add(U64.modulus) else bi
  }
}

object U64 {
  import java.lang.{Long => JLong}

  private[U64] val modulus = BigInteger.valueOf(1).shiftLeft(java.lang.Long.SIZE)

  def validate(value: BigInteger): Boolean = {
    Numeric.nonNegative(value) && value.bitLength() <= 64
  }

  def unsafe(value: Long): U64 = new U64(value)

  def from(value: Long): Option[U64] = if (value >= 0) Some(unsafe(value)) else None

  def from(value: BigInteger): Option[U64] =
    try {
      if (validate(value)) {
        Some(unsafe(value.longValue()))
      } else None
    } catch {
      case _: ArithmeticException => None
    }

  def fromI64(value: I64): Option[U64] = {
    if (value.isPositive) Some(unsafe(value.v)) else None
  }

  def fromI256(value: I256): Option[U64] = {
    from(value.v)
  }

  def fromU256(value: U256): Option[U64] = {
    from(value.v)
  }

  val Zero: U64     = unsafe(0)
  val One: U64      = unsafe(1)
  val Two: U64      = unsafe(2)
  val MaxValue: U64 = unsafe(-1)
  val MinValue: U64 = Zero

  // scalastyle:off magic.number
  val Million: U64 = unsafe(1000000)
  val Billion: U64 = unsafe(1000000000)
  // scalastyle:on magic.number

  @inline private def checkAdd(a: U64, c: Long): Boolean = {
    JLong.compareUnsigned(c, a.v) >= 0
  }

  @inline private def checkSub(a: U64, b: U64): Boolean = {
    JLong.compareUnsigned(a.v, b.v) >= 0
  }

  // assume a != 0
  @inline private def checkMul(a: U64, b: U64, c: Long): Boolean = {
    JLong.divideUnsigned(c, a.v) == b.v
  }
}
