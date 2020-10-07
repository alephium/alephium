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

class I64(val v: Long) extends AnyVal with Ordered[I64] {
  import java.lang.{Long => JLong}

  @inline def isZero: Boolean = v == 0

  def isPositive: Boolean = v >= 0

  def addUnsafe(that: I64): I64 = {
    val underlying = this.v + that.v
    assume(I64.checkAdd(this, that, underlying))
    I64.from(underlying)
  }

  def add(that: I64): Option[I64] = {
    val underlying = this.v + that.v
    if (I64.checkAdd(this, that, underlying)) {
      Some(I64.from(underlying))
    } else None
  }

  def subUnsafe(that: I64): I64 = {
    val underlying = this.v - that.v
    assume(I64.checkSub(this, that, underlying))
    I64.from(underlying)
  }

  def sub(that: I64): Option[I64] = {
    val underlying = this.v - that.v
    if (I64.checkSub(this, that, underlying)) {
      Some(I64.from(underlying))
    } else None
  }

  def mulUnsafe(that: I64): I64 = {
    if (this.v == 0) I64.Zero
    else {
      val underlying = this.v * that.v
      assume(I64.checkMul(this, that, underlying))
      I64.from(underlying)
    }
  }

  def mul(that: I64): Option[I64] = {
    if (this.v == 0) Some(I64.Zero)
    else {
      val underlying = this.v * that.v
      if (I64.checkMul(this, that, underlying)) {
        Some(I64.from(underlying))
      } else None
    }
  }

  def divUnsafe(that: I64): I64 = {
    assume(I64.checkDiv(this, that))
    I64.from(this.v / that.v)
  }

  def div(that: I64): Option[I64] = {
    if (!I64.checkDiv(this, that)) None
    else Some(I64.from(this.v / that.v))
  }

  def modUnsafe(that: I64): I64 = {
    assume(!that.isZero)
    I64.from(this.v % that.v)
  }

  def mod(that: I64): Option[I64] = {
    if (that.isZero) None else Some(I64.from(this.v % that.v))
  }

  def compare(that: I64): Int = JLong.compare(this.v, that.v)

  def toBigInt: BigInteger = BigInteger.valueOf(v)
}

object I64 {
  def from(value: Long): I64 = new I64(value)

  def validate(value: BigInteger): Boolean = {
    value.bitLength() <= 63
  }

  def from(value: BigInteger): Option[I64] =
    try {
      Some(from(value.longValueExact()))
    } catch {
      case _: ArithmeticException => None
    }

  def fromU64(value: U64): Option[I64] = {
    if (value.v >= 0) Some(from(value.v)) else None
  }

  def fromI256(value: I256): Option[I64] = {
    from(value.v)
  }

  def fromU256(value: U256): Option[I64] = {
    from(value.v)
  }

  val Zero: I64     = from(0)
  val One: I64      = from(1)
  val Two: I64      = from(2)
  val NegOne: I64   = from(-1)
  val MinValue: I64 = from(Long.MinValue)
  val MaxValue: I64 = from(Long.MaxValue)

  @inline private def checkAdd(a: I64, b: I64, c: Long): Boolean = {
    (b.v >= 0 && c >= a.v) || (b.v < 0 && c < a.v)
  }

  @inline private def checkSub(a: I64, b: I64, c: Long): Boolean = {
    (b.v >= 0 && c <= a.v) || (b.v < 0 && c > a.v)
  }

  // assume a != 0
  @inline private def checkMul(a: I64, b: I64, c: Long): Boolean = {
    !(a.v == -1 && b.v == Long.MinValue) && (c / a.v == b.v)
  }

  // assume b != 0
  @inline private def checkDiv(a: I64, b: I64): Boolean = {
    b.v != 0 && (b.v != -1 || a.v != Long.MinValue)
  }
}
