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

class I256Spec extends AlephiumSpec {
  val numGen = (0L to 3L).flatMap { i =>
    val n = BigInteger.valueOf(i)
    List(
      n.subtract(BigInteger.ONE),
      I256.MinValue.toBigInt.add(n),
      I256.MinValue.divUnsafe(I256.Two).toBigInt.add(n),
      I256.MaxValue.toBigInt.subtract(n),
      I256.MaxValue.divUnsafe(I256.Two).toBigInt.add(n),
      SecureAndSlowRandom.nextI256().toBigInt,
      SecureAndSlowRandom.nextI256().toBigInt
    )
  }

  it should "be bounded properly" in {
    I256.upperBound.subtract(I256.lowerBound) is BigInteger.valueOf(2).pow(256)
    I256.validate(I256.lowerBound) is true
    I256.validate(I256.lowerBound.add(BigInteger.ONE)) is true
    I256.validate(I256.lowerBound.subtract(BigInteger.ONE)) is false
    I256.validate(I256.upperBound) is false
    I256.validate(I256.upperBound.subtract(BigInteger.ONE)) is true
  }

  it should "convert to BigInt" in {
    I256.Zero.toBigInt is BigInteger.ZERO
    I256.One.toBigInt is BigInteger.ONE
    I256.MaxValue.toBigInt is BigInteger.valueOf(2).pow(255).subtract(BigInteger.ONE)
    I256.MinValue.toBigInt is BigInteger.valueOf(2).pow(255).negate()
  }

  it should "convert from BigInt" in {
    I256.from(I256.MinValue.toBigInt).get is I256.MinValue
    I256.from(I256.MaxValue.toBigInt).get is I256.MaxValue
    I256.from(I256.MinValue.toBigInt.subtract(BigInteger.ONE)).isEmpty is true
    I256.from(I256.MaxValue.toBigInt.add(BigInteger.ONE)).isEmpty is true
  }

  def test(
      op: (I256, I256) => Option[I256],
      opUnsafe: (I256, I256) => I256,
      opExpected: (BigInteger, BigInteger) => BigInteger,
      condition: (BigInteger, BigInteger) => Boolean = _ >= I256.lowerBound && _ >= I256.lowerBound
  ): Unit = {
    for {
      a <- numGen
      b <- numGen
    } {
      val aI256         = I256.unsafe(a)
      val bI256         = I256.unsafe(b)
      lazy val expected = opExpected(aI256.toBigInt, bI256.toBigInt)
      if (condition(a, b) && expected >= I256.lowerBound && expected < I256.upperBound) {
        op(aI256, bI256).get.toBigInt is expected
        opUnsafe(aI256, bI256).toBigInt is expected
      } else {
        assertThrows[AssertionError](opUnsafe(aI256, bI256))
        op(aI256, bI256).isEmpty is true
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
    test(
      _.div(_),
      _.divUnsafe(_),
      _.divide(_),
      (a, b) =>
        b != BigInteger.ZERO && !(a.equals(I256.lowerBound) && b.equals(I256.NegOne.toBigInt))
    )
  }

  it should "test mod" in {
    test(
      _.mod(_),
      _.modUnsafe(_),
      _.remainder(_),
      (a, b) =>
        b != BigInteger.ZERO && !(a.equals(I256.lowerBound) && b.equals(I256.NegOne.toBigInt))
    )
  }

  it should "compare I256" in {
    for {
      a <- numGen
      b <- numGen
    } {
      val aI256 = I256.unsafe(a)
      val bI256 = I256.unsafe(b)
      aI256.compareTo(bI256) is a.compareTo(b)
    }
  }

  it should "convert to/from bytes" in {
    val cases = List(I256.Zero, I256.One, I256.NegOne, I256.MaxValue, I256.MinValue)
    for (i256 <- cases) {
      I256.unsafe(i256.toBytes) is i256
    }
  }

  it should "construct from Long" in {
    forAll { x: Long => I256.from(x).toBigInt is BigInteger.valueOf(x) }
  }

  it should "convert from ByteString" in {
    for {
      x <- numGen
    } {
      val byteString = ByteString.fromArrayUnsafe(x.toByteArray())
      I256.from(byteString).get.v is x
    }
  }

  it should "convert from U256" in {
    val u256Gen = List(I256.MaxValue.toBigInt.add(BigInteger.ONE), U256.MaxValue.toBigInt)
    for {
      x <- numGen.appendedAll(u256Gen)
    } {
      if (x >= BigInteger.ZERO && x < U256.upperBound) {
        val expected = if (x < I256.upperBound) I256.from(x) else None
        I256.fromU256(U256.unsafe(x)) is expected
      } else {
        assertThrows[AssertionError](I256.fromU256(U256.unsafe(x)))
      }
    }
  }

  it should "convert to byte" in {
    for {
      x <- numGen
    } {
      val byte = I256.from(x).get.toByte
      if (x >= BigInteger.valueOf(Byte.MinValue) && x <= BigInteger.valueOf(Byte.MaxValue)) {
        byte.get is x.byteValue()
      } else {
        byte is None
      }
    }
  }

  it should "convert to int" in {
    for {
      x <- numGen
    } {
      val value = I256.from(x).get.toInt
      if (x >= BigInteger.valueOf(Int.MinValue) && x <= BigInteger.valueOf(Int.MaxValue)) {
        value.get is x.intValue()
      } else {
        value is None
      }
    }
  }

  it should "convert from Int" in {
    forAll { x: Int =>
      I256.from(x).v.longValue() is x.toLong
    }
  }
}
