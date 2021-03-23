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

class U32Spec extends AlephiumSpec {
  val numGen = (0 to 4).flatMap(i => List(i - 2, Int.MinValue + i, Int.MaxValue - i))

  it should "convert to BigInt" in {
    U32.Zero.toBigInt is BigInteger.ZERO
    U32.One.toBigInt is BigInteger.ONE
    U32.Two.toBigInt is BigInteger.valueOf(2)
    U32.MaxValue.toBigInt is BigInteger.valueOf(2).pow(32).subtract(BigInteger.ONE)
  }

  it should "convert from BigInt" in {
    U32.from(U32.MinValue.toBigInt).get is U32.MinValue
    U32.from(U32.MaxValue.toBigInt).get is U32.MaxValue
    U32.from(U32.MinValue.toBigInt.subtract(BigInteger.ONE)).isEmpty is true
    U32.from(U32.MaxValue.toBigInt.add(BigInteger.ONE)).isEmpty is true
  }

  def test(
      op: (U32, U32) => Option[U32],
      opUnsafe: (U32, U32) => U32,
      opExpected: (BigInteger, BigInteger) => BigInteger,
      condition: Int => Boolean = _ >= Int.MinValue
  ): Unit = {
    for {
      a <- numGen
      b <- numGen
    } {
      val aU32          = U32.unsafe(a)
      val bU32          = U32.unsafe(b)
      lazy val expected = opExpected(aU32.toBigInt, bU32.toBigInt)
      if (condition(b) && expected >= BigInteger.ZERO && expected <= U32.MaxValue.toBigInt) {
        op(aU32, bU32).get.toBigInt is expected
        opUnsafe(aU32, bU32).toBigInt is expected
      } else {
        assertThrows[AssertionError](opUnsafe(aU32, bU32))
        op(aU32, bU32).isEmpty is true
      }
    }
  }

  it should "test add" in {
    test(_.add(_), _.addUnsafe(_), _.add(_))
  }

  it should "test sub" in {
    test(_.sub(_), _.subUnsafe(_), _.subtract(_))
  }

  it should "test mul" in {
    test(_.mul(_), _.mulUnsafe(_), _.multiply(_))
  }

  it should "test div" in {
    test(_.div(_), _.divUnsafe(_), _.divide(_), _ != 0)
  }

  it should "test mod" in {
    test(_.mod(_), _.modUnsafe(_), _.remainder(_), _ != 0)
  }

  it should "compare U32" in {
    for {
      a <- numGen
      b <- numGen
    } {
      val aU32 = U32.unsafe(a)
      val bU32 = U32.unsafe(b)
      aU32.compareTo(bU32) is Integer.compareUnsigned(a, b)
    }
  }
}
