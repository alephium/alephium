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

class I32(val v: Int) extends AnyVal with Ordered[I32] {
  import java.lang.{Integer => JInt}

  @inline def isZero: Boolean = v == 0

  def addUnsafe(that: I32): I32 = {
    val underlying = this.v + that.v
    assume(I32.checkAdd(this, that, underlying))
    I32.unsafe(underlying)
  }

  def add(that: I32): Option[I32] = {
    val underlying = this.v + that.v
    if (I32.checkAdd(this, that, underlying)) {
      Some(I32.unsafe(underlying))
    } else None
  }

  def subUnsafe(that: I32): I32 = {
    val underlying = this.v - that.v
    assume(I32.checkSub(this, that, underlying))
    I32.unsafe(underlying)
  }

  def sub(that: I32): Option[I32] = {
    val underlying = this.v - that.v
    if (I32.checkSub(this, that, underlying)) {
      Some(I32.unsafe(underlying))
    } else None
  }

  def mulUnsafe(that: I32): I32 = {
    if (this.v == 0) I32.Zero
    else {
      val underlying = this.v * that.v
      assume(I32.checkMul(this, that, underlying))
      I32.unsafe(underlying)
    }
  }

  def mul(that: I32): Option[I32] = {
    if (this.v == 0) Some(I32.Zero)
    else {
      val underlying = this.v * that.v
      if (I32.checkMul(this, that, underlying)) {
        Some(I32.unsafe(underlying))
      } else None
    }
  }

  def divUnsafe(that: I32): I32 = {
    assume(I32.checkDiv(this, that))
    I32.unsafe(this.v / that.v)
  }

  def div(that: I32): Option[I32] = {
    if (!I32.checkDiv(this, that)) None
    else Some(I32.unsafe(this.v / that.v))
  }

  def modUnsafe(that: I32): I32 = {
    assume(!that.isZero)
    I32.unsafe(this.v % that.v)
  }

  def mod(that: I32): Option[I32] = {
    if (that.isZero) None else Some(I32.unsafe(this.v % that.v))
  }

  def compare(that: I32): Int = JInt.compare(this.v, that.v)

  def toBigInt: BigInteger = BigInteger.valueOf(v.toLong)
}

object I32 {
  def unsafe(value: Int): I32 = new I32(value)

  def from(value: Int): Option[I32] = if (value >= 0) Some(unsafe(value)) else None

  def validate(value: BigInteger): Boolean = {
    value.bitLength() <= 31
  }

  def from(value: BigInteger): Option[I32] =
    try {
      Some(unsafe(value.intValueExact()))
    } catch {
      case _: ArithmeticException => None
    }

  val Zero: I32     = unsafe(0)
  val One: I32      = unsafe(1)
  val Two: I32      = unsafe(2)
  val NegOne: I32   = unsafe(-1)
  val MinValue: I32 = unsafe(Int.MinValue)
  val MaxValue: I32 = unsafe(Int.MaxValue)

  @inline private def checkAdd(a: I32, b: I32, c: Int): Boolean = {
    (b.v >= 0 && c >= a.v) || (b.v < 0 && c < a.v)
  }

  @inline private def checkSub(a: I32, b: I32, c: Int): Boolean = {
    (b.v >= 0 && c <= a.v) || (b.v < 0 && c > a.v)
  }

  // assume a != 0
  @inline private def checkMul(a: I32, b: I32, c: Int): Boolean = {
    !(a.v == -1 && b.v == Int.MinValue) && (c / a.v == b.v)
  }

  // assume b != 0
  @inline private def checkDiv(a: I32, b: I32): Boolean = {
    b.v != 0 && (b.v != -1 || a.v != Int.MinValue)
  }
}
