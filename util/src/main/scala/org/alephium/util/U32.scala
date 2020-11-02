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

class U32(val v: Int) extends AnyVal with Ordered[U32] {
  import java.lang.{Integer => JInt}

  @inline def isZero: Boolean = v == 0

  def addUnsafe(that: U32): U32 = {
    val underlying = this.v + that.v
    assume(U32.checkAdd(this, underlying))
    U32.unsafe(underlying)
  }

  def add(that: U32): Option[U32] = {
    val underlying = this.v + that.v
    if (U32.checkAdd(this, underlying)) Some(U32.unsafe(underlying)) else None
  }

  def subUnsafe(that: U32): U32 = {
    assume(U32.checkSub(this, that))
    U32.unsafe(this.v - that.v)
  }

  def sub(that: U32): Option[U32] = {
    if (U32.checkSub(this, that)) {
      Some(U32.unsafe(this.v - that.v))
    } else {
      None
    }
  }

  def mulUnsafe(that: U32): U32 = {
    if (this.v == 0) {
      U32.Zero
    } else {
      val underlying = this.v * that.v
      assume(U32.checkMul(this, that, underlying))
      U32.unsafe(underlying)
    }
  }

  def mul(that: U32): Option[U32] = {
    if (this.v == 0) {
      Some(U32.Zero)
    } else {
      val underlying = this.v * that.v
      if (U32.checkMul(this, that, underlying)) {
        Some(U32.unsafe(underlying))
      } else {
        None
      }
    }
  }

  def divUnsafe(that: U32): U32 = {
    assume(!that.isZero)
    U32.unsafe(JInt.divideUnsigned(this.v, that.v))
  }

  def div(that: U32): Option[U32] = {
    if (that.isZero) None else Some(U32.unsafe(JInt.divideUnsigned(this.v, that.v)))
  }

  def modUnsafe(that: U32): U32 = {
    assume(!that.isZero)
    U32.unsafe(JInt.remainderUnsigned(this.v, that.v))
  }

  def mod(that: U32): Option[U32] = {
    if (that.isZero) None else Some(U32.unsafe(JInt.remainderUnsigned(this.v, that.v)))
  }

  def compare(that: U32): Int = JInt.compareUnsigned(this.v, that.v)

  def toBigInt: BigInteger = {
    BigInteger.valueOf(Integer.toUnsignedLong(v))
  }
}

object U32 {
  import java.lang.{Integer => JInt}

  def validate(value: BigInteger): Boolean = {
    Number.nonNegative(value) && value.bitLength() <= 32
  }

  def unsafe(value: Int): U32 = new U32(value)

  def from(value: Int): Option[U32] = if (value >= 0) Some(unsafe(value)) else None

  def from(value: BigInteger): Option[U32] =
    try {
      if (validate(value)) {
        Some(unsafe(value.intValue()))
      } else {
        None
      }
    } catch {
      case _: ArithmeticException => None
    }

  val Zero: U32     = unsafe(0)
  val One: U32      = unsafe(1)
  val Two: U32      = unsafe(2)
  val MaxValue: U32 = unsafe(-1)
  val MinValue: U32 = Zero

  @inline private def checkAdd(a: U32, c: Int): Boolean = {
    JInt.compareUnsigned(c, a.v) >= 0
  }

  @inline private def checkSub(a: U32, b: U32): Boolean = {
    JInt.compareUnsigned(a.v, b.v) >= 0
  }

  // assume a != 0
  @inline private def checkMul(a: U32, b: U32, c: Int): Boolean = {
    JInt.divideUnsigned(c, a.v) == b.v
  }
}
