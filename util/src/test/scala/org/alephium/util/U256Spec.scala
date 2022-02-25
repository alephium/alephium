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
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

class U256Spec extends AlephiumSpec {
  val numGen = (0 to 3).flatMap { i =>
    val n = BigInteger.valueOf(i.toLong)
    List(
      n,
      U256.MaxValue.divUnsafe(U256.Two).toBigInt.add(n),
      U256.MaxValue.toBigInt.subtract(n),
      SecureAndSlowRandom.nextU256().toBigInt,
      SecureAndSlowRandom.nextU256().toBigInt
    )
  }

  it should "be bounded properly" in {
    U256.upperBound.subtract(BigInteger.ZERO) is BigInteger.valueOf(2).pow(256)
    U256.validate(BigInteger.ZERO) is true
    U256.validate(BigInteger.ONE) is true
    U256.validate(BigInteger.ONE.negate()) is false
    U256.validate(U256.upperBound) is false
    U256.validate(U256.upperBound.subtract(BigInteger.ONE)) is true
  }

  it should "convert to BigInt" in {
    U256.Zero.toBigInt is BigInteger.ZERO
    U256.One.toBigInt is BigInteger.ONE
    U256.MaxValue.toBigInt is BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE)
  }

  it should "convert from BigInt" in {
    U256.from(U256.MinValue.toBigInt).get is U256.MinValue
    U256.from(U256.MaxValue.toBigInt).get is U256.MaxValue
    U256.from(U256.MinValue.toBigInt.subtract(BigInteger.ONE)).isEmpty is true
    U256.from(U256.MaxValue.toBigInt.add(BigInteger.ONE)).isEmpty is true
  }

  def test(
      op: (U256, U256) => Option[U256],
      opUnsafe: (U256, U256) => U256,
      opExpected: (BigInteger, BigInteger) => BigInteger,
      condition: BigInteger => Boolean = _ >= BigInteger.ZERO
  ): Unit = {
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

  def test[R, S](
      op: (U256, U256) => R,
      opExpected: (BigInteger, BigInteger) => S,
      r2s: R => S
  ): Unit = {
    for {
      a <- numGen
      b <- numGen
    } {
      val aU256 = U256.unsafe(a)
      val bU256 = U256.unsafe(b)
      r2s(op(aU256, bU256)) is opExpected(a, b)
    }
  }

  it should "test mod_add" in {
    test[U256, BigInteger](_.modAdd(_), _.add(_).mod(U256.upperBound), _.toBigInt)
  }

  it should "test mod_sub" in {
    test[U256, BigInteger](_.modSub(_), _.subtract(_).mod(U256.upperBound), _.toBigInt)
  }

  it should "test mod_mul" in {
    test[U256, BigInteger](_.modMul(_), _.multiply(_).mod(U256.upperBound), _.toBigInt)
  }

  it should "test bit_and" in {
    test[U256, BigInteger](_.bitAnd(_), _.and(_), _.toBigInt)
  }

  it should "test bit_or" in {
    test[U256, BigInteger](_.bitOr(_), _.or(_), _.toBigInt)
  }

  it should "test xor" in {
    test[U256, BigInteger](_.xor(_), _.xor(_), _.toBigInt)
  }

  it should "compare U256" in {
    test[Int, Int](_.compareTo(_), _.compareTo(_), identity)
  }

  it should "test shift" in {
    for {
      x <- numGen
      n <- Seq(0, 1, 2, 8, 16, 64, 256, Int.MaxValue).map(U256.unsafe) ++ numGen.map(U256.unsafe)
    } {
      val xU256    = U256.unsafe(x)
      val nBounded = if (n > U256.unsafe(256)) 256 else n.toBigInt.intValue()
      xU256.shl(n).toBigInt is x.shiftLeft(nBounded).mod(U256.upperBound)
      xU256.shr(n).toBigInt is x.shiftRight(nBounded).mod(U256.upperBound)
    }
  }

  it should "convert to/from bytes" in {
    val cases = List(U256.Zero, U256.One, U256.MaxValue, U256.HalfMaxValue, U256.MinValue)
    for (u256 <- cases) {
      U256.unsafe(u256.toBytes) is u256
    }
  }

  it should "bound bigint" in {
    for (n <- numGen) {
      U256.boundNonNegative(U256.upperBound.add(n)) is U256.unsafe(n)
    }
    assertThrows[AssertionError](U256.boundNonNegative(BigInteger.valueOf(-1)))

    U256.boundSub(U256.Zero.v.subtract(U256.MaxValue.v)) is U256.One
    U256.boundSub(U256.MaxValue.v.subtract(U256.Zero.v)) is U256.MaxValue
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

  it should "increase/decrease by one" in {
    U256.Zero.addOneUnsafe() is U256.One
    U256.One.addOneUnsafe() is U256.Two
    U256.Two.subOneUnsafe() is U256.One
    U256.One.subOneUnsafe() is U256.Zero

    assertThrows[AssertionError](U256.MaxValue.addOneUnsafe())
    assertThrows[AssertionError](U256.Zero.subOneUnsafe())
  }

  it should "convert to int" in {
    U256.unsafe(0).toInt.get is 0
    U256.unsafe(Int.MaxValue).toInt.get is Int.MaxValue
    U256.unsafe(Int.MaxValue.toLong + 1).toInt is None
  }

  it should "return if nonZero" in {
    for {
      x <- numGen
    } {
      U256.unsafe(x).nonZero is x != BigInteger.ZERO
    }
  }

  it should "convert from ByteString" in {
    for {
      x <- numGen
    } {
      val byteString = ByteString.fromArrayUnsafe(x.toByteArray())
      U256.from(byteString).get.v is x
    }

    val maxPlusOne = ByteString.fromArrayUnsafe(U256.upperBound.toByteArray)
    maxPlusOne.length is 33
    U256.from(maxPlusOne).isEmpty is true
  }

  it should "convert from Int" in {
    forAll { x: Int =>
      if (x >= 0) {
        U256.unsafe(x).v.longValue() is x.toLong
      } else {
        assertThrows[AssertionError](U256.unsafe(x))
      }
    }
  }

  it should "convert from I256" in {
    val i256Gen = List(I256.NegOne, I256.MinValue).map(_.toBigInt)
    for {
      x <- numGen.appendedAll(i256Gen)
    } {
      if (x >= I256.lowerBound && x < I256.upperBound) {
        val expected = if (x >= BigInteger.ZERO) U256.from(x) else None
        U256.fromI256(I256.unsafe(x)) is expected
      } else {
        assertThrows[AssertionError](U256.fromI256(I256.unsafe(x)))
      }
    }
  }

  it should "convert to byte" in {
    for {
      x <- numGen
    } {
      val byte = U256.from(x).get.toByte
      if (x >= BigInteger.ZERO && x <= BigInteger.valueOf(Byte.MaxValue)) {
        byte.get is x.byteValue()
      } else {
        byte is None
      }
    }
  }

  it should "convert to Long" in {
    U256.unsafe(0).toLong.get is 0
    U256.unsafe(Long.MaxValue).toLong.get is Long.MaxValue
    U256.unsafe(Long.MaxValue).addUnsafe(U256.One).toLong is None
  }

  it should "convert to fixed size bytes" in {
    Seq(1, 2, 4, 8, 16, 32).foreach { size =>
      forAll(Gen.listOfN(size, arbitrary[Byte])) { bytes =>
        val byteString = ByteString(bytes)
        val value      = U256.from(byteString).get
        val encoded    = value.toFixedSizeBytes(size).get
        encoded.length is size
        encoded is byteString
      }

      U256.Zero.toFixedSizeBytes(size) is Some(ByteString(Array.fill[Byte](size)(0)))
      U256.from(BigInteger.ONE.shiftLeft(size * 8)).flatMap(_.toFixedSizeBytes(size)) is None
      U256
        .unsafe(BigInteger.ONE.shiftLeft(size * 8).subtract(BigInteger.ONE))
        .toFixedSizeBytes(size) is Some(ByteString(Array.fill[Byte](size)(-1)))
    }
  }
}
