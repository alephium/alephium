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

class I32Spec extends AlephiumSpec {
  val numGen = (0 to 4).flatMap(i => List(i - 2, Int.MinValue + i, Int.MaxValue - i))

  it should "convert to BigInt" in {
    I32.Zero.toBigInt is BigInteger.ZERO
    I32.One.toBigInt is BigInteger.ONE
    I32.Two.toBigInt is BigInteger.TWO
    I32.NegOne.toBigInt is BigInteger.ONE.negate()
    I32.MinValue.toBigInt is BigInteger.TWO.pow(31).negate()
    I32.MaxValue.toBigInt is BigInteger.TWO.pow(31).subtract(BigInteger.ONE)
  }

  it should "convert from BigInt" in {
    I32.from(I32.MinValue.toBigInt).get is I32.MinValue
    I32.from(I32.MaxValue.toBigInt).get is I32.MaxValue
    I32.from(I32.MinValue.toBigInt.subtract(BigInteger.ONE)).isEmpty is true
    I32.from(I32.MaxValue.toBigInt.add(BigInteger.ONE)).isEmpty is true
  }

  def test(op: (I32, I32)                       => Option[I32],
           opUnsafe: (I32, I32)                 => I32,
           opExpected: (BigInteger, BigInteger) => BigInteger,
           bcondition: Int                      => Boolean = _ >= Int.MinValue,
           abcondition: (Int, Int)              => Boolean = _ + _ >= Int.MinValue): Unit = {
    for {
      a <- numGen
      b <- numGen
    } {
      val aI32          = I32.unsafe(a)
      val bI32          = I32.unsafe(b)
      lazy val expected = opExpected(aI32.toBigInt, bI32.toBigInt)
      if (bcondition(b) && abcondition(a, b) && expected >= I32.MinValue.toBigInt && expected <= I32.MaxValue.toBigInt) {
        op(aI32, bI32).get.toBigInt is expected
        opUnsafe(aI32, bI32).toBigInt is expected
      } else {
        assertThrows[AssertionError](opUnsafe(aI32, bI32))
        op(aI32, bI32).isEmpty is true
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
    test(_.div(_), _.divUnsafe(_), _.divide(_), _ != 0, _ != Int.MinValue || _ != -1)
  }

  it should "test mod" in {
    test(_.mod(_), _.modUnsafe(_), _.remainder(_), _ != 0)
  }

  it should "compare I32" in {
    for {
      a <- numGen
      b <- numGen
    } {
      val aI32 = I32.unsafe(a)
      val bI32 = I32.unsafe(b)
      aI32.compareTo(bI32) is Integer.compare(a, b)
    }
  }
}
