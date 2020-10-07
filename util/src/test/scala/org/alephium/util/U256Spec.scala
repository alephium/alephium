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

class U256Spec extends AlephiumSpec {
  val numGen = (0 to 3).flatMap { i =>
    val n = BigInteger.valueOf(i.toLong)
    List(n, U256.MaxValue.divUnsafe(U256.Two).toBigInt.add(n), U256.MaxValue.toBigInt.subtract(n))
  }

  it should "be bounded properly" in {
    U256.upperBound.subtract(BigInteger.ZERO) is BigInteger.TWO.pow(256)
    U256.validate(BigInteger.ZERO) is true
    U256.validate(BigInteger.ONE) is true
    U256.validate(BigInteger.ONE.negate()) is false
    U256.validate(U256.upperBound) is false
    U256.validate(U256.upperBound.subtract(BigInteger.ONE)) is true
  }

  it should "convert to BigInt" in {
    U256.Zero.toBigInt is BigInteger.ZERO
    U256.One.toBigInt is BigInteger.ONE
    U256.MaxValue.toBigInt is BigInteger.TWO.pow(256).subtract(BigInteger.ONE)
  }

  it should "convert from BigInt" in {
    U256.from(U256.MinValue.toBigInt).get is U256.MinValue
    U256.from(U256.MaxValue.toBigInt).get is U256.MaxValue
    U256.from(U256.MinValue.toBigInt.subtract(BigInteger.ONE)).isEmpty is true
    U256.from(U256.MaxValue.toBigInt.add(BigInteger.ONE)).isEmpty is true
  }

  def test(op: (U256, U256)                     => Option[U256],
           opUnsafe: (U256, U256)               => U256,
           opExpected: (BigInteger, BigInteger) => BigInteger,
           condition: BigInteger                => Boolean = _ >= BigInteger.ZERO): Unit = {
    for {
      a <- numGen
      b <- numGen
    } {
      val aU256         = U256.unsafe(a)
      val bU256         = U256.unsafe(b)
      lazy val expected = opExpected(aU256.toBigInt, bU256.toBigInt)
      if (condition(b) && expected >= BigInteger.ZERO && expected < U256.upperBound) {
        op(aU256, bU256).get.toBigInt is expected
        opUnsafe(aU256, bU256).toBigInt is expected
      } else {
        assertThrows[AssertionError](opUnsafe(aU256, bU256))
        op(aU256, bU256).isEmpty is true
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
    test(_.div(_), _.divUnsafe(_), _.divide(_), _ > BigInteger.ZERO)
  }

  it should "test mod" in {
    test(_.mod(_), _.modUnsafe(_), _.remainder(_), _ > BigInteger.ZERO)
  }

  it should "compare U256" in {
    for {
      a <- numGen
      b <- numGen
    } {
      val aU256 = U256.unsafe(a)
      val bU256 = U256.unsafe(b)
      aU256.compareTo(bU256) is a.compareTo(b)
    }
  }

  it should "convert to/from bytes" in {
    val cases = List(U256.Zero, U256.One, U256.MaxValue, U256.MinValue)
    for (u256 <- cases) {
      U256.unsafe(u256.toBytes) is u256
    }
  }

  it should "construct from Long" in {
    forAll { x: Long =>
      if (x >= 0) {
        U256.unsafe(x).toBigInt is BigInteger.valueOf(x)
      } else {
        assertThrows[AssertionError](U256.unsafe(x))
      }
    }
  }
}
