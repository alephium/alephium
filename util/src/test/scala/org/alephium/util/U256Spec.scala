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

  it should "test shift deprecated" in {
    for {
      x <- numGen
      n <- Seq(0, 1, 2, 8, 16, 64, 256, Int.MaxValue).map(U256.unsafe) ++ numGen.map(U256.unsafe)
    } {
      val xU256    = U256.unsafe(x)
      val nBounded = if (n > U256.unsafe(256)) 256 else n.toBigInt.intValue()
      xU256.shlDeprecated(n).toBigInt is x.shiftLeft(nBounded).mod(U256.upperBound)
      xU256.shr(n).toBigInt is x.shiftRight(nBounded).mod(U256.upperBound)
    }
  }

  it should "test shift" in {
    for {
      x <- numGen
      n <- Seq(0, 1, 2, 8, 16, 64, 255, 256, Int.MaxValue).map(U256.unsafe) ++ numGen.map(
        U256.unsafe
      )
    } {
      val xU256    = U256.unsafe(x)
      val nBounded = if (n > U256.unsafe(512)) 512 else n.toBigInt.intValue()

      val expectedShl = x.multiply(BigInteger.TWO.pow(nBounded))
      if (U256.validate(expectedShl)) {
        xU256.shl(n).value.toBigInt is expectedShl
      } else {
        xU256.shl(n) is None
      }

      val expectedShr = x.divide(BigInteger.TWO.pow(nBounded))
      xU256.shr(n).v is expectedShr
    }
  }

  it should "test shift with edge cases" in {
    // Test shl with zero shift amount
    U256.Zero.shl(U256.Zero).value is U256.Zero
    U256.One.shl(U256.Zero).value is U256.One
    U256.Two.shl(U256.Zero).value is U256.Two
    U256.MaxValue.shl(U256.Zero).value is U256.MaxValue

    // Test shl with one shift amount
    U256.Zero.shl(U256.One).value is U256.unsafe(0)
    U256.One.shl(U256.One).value is U256.unsafe(2)
    U256.Two.shl(U256.One).value is U256.unsafe(4)
    U256.MaxValue.shl(U256.One) is None
    U256.MaxValue.subUnsafe(U256.One).shl(U256.One) is None
    U256.MaxValue.shr(U256.One).shl(U256.One).value is U256.MaxValue.subUnsafe(U256.One)

    // Test shl with 255 shift amount
    U256.Zero.shl(U256.unsafe(255)).value is U256.unsafe(0)
    U256.One.shl(U256.unsafe(255)).value is U256.HalfMaxValue.addOneUnsafe()
    U256.Two.shl(U256.unsafe(255)) is None
    U256.MaxValue.shl(U256.unsafe(255)) is None

    // Test shl with 256 shift amount
    U256.Zero.shl(U256.unsafe(256)).value is U256.unsafe(0)
    U256.One.shl(U256.unsafe(256)) is None
    U256.Two.shl(U256.unsafe(256)) is None
    U256.MaxValue.shl(U256.unsafe(256)) is None

    // Test shl with max shift amount
    U256.Zero.shl(U256.MaxValue).value is U256.Zero
    U256.One.shl(U256.MaxValue) is None
    U256.Two.shl(U256.MaxValue) is None
    U256.MaxValue.shl(U256.MaxValue) is None

    // Test shr with zero shift amount
    U256.Zero.shr(U256.Zero) is U256.Zero
    U256.One.shr(U256.Zero) is U256.One
    U256.Two.shr(U256.Zero) is U256.Two
    U256.MaxValue.shr(U256.Zero) is U256.MaxValue

    // Test shr with one shift amount
    U256.Zero.shr(U256.One) is U256.unsafe(0)
    U256.One.shr(U256.One) is U256.unsafe(0)
    U256.Two.shr(U256.One) is U256.unsafe(1)
    U256.MaxValue.shr(U256.One) is U256.HalfMaxValue

    // Test shr with 255 shift amount
    U256.Zero.shr(U256.unsafe(255)) is U256.unsafe(0)
    U256.One.shr(U256.unsafe(255)) is U256.unsafe(0)
    U256.HalfMaxValue.addOneUnsafe().shr(U256.unsafe(255)) is U256.unsafe(1)
    U256.MaxValue.shr(U256.unsafe(255)) is U256.unsafe(1)

    // Test shr with 256 shift amount
    U256.Zero.shr(U256.unsafe(256)) is U256.unsafe(0)
    U256.One.shr(U256.unsafe(256)) is U256.unsafe(0)
    U256.Two.shr(U256.unsafe(256)) is U256.unsafe(0)
    U256.MaxValue.shr(U256.unsafe(256)) is U256.unsafe(0)

    // Test shr with max shift amount
    U256.Zero.shr(U256.MaxValue) is U256.Zero
    U256.One.shr(U256.MaxValue) is U256.Zero
    U256.Two.shr(U256.MaxValue) is U256.Zero
    U256.MaxValue.shr(U256.MaxValue) is U256.Zero
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
    forAll { (x: Long) =>
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
    forAll { (x: Int) =>
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

  it should "test pow" in {
    U256.Zero.v.bitLength() is 0
    U256.Zero.pow(U256.Zero) is Some(U256.One)
    U256.Zero.pow(U256.One) is Some(U256.Zero)
    U256.Zero.pow(U256.MaxValue) is Some(U256.Zero)

    U256.One.v.bitLength() is 1
    U256.One.pow(U256.Zero) is Some(U256.One)
    U256.One.pow(U256.One) is Some(U256.One)
    U256.One.pow(U256.MaxValue) is Some(U256.One)

    U256.Two.v.bitLength() is 2
    U256.Two.pow(U256.Zero) is Some(U256.One)
    U256.Two.pow(U256.One) is Some(U256.Two)
    U256.Two.pow(U256.unsafe(255)) is Some(U256.HalfMaxValue.addOneUnsafe())
    U256.HalfMaxValue.addOneUnsafe().v is U256.upperBound.divide(2)
    U256.Two.pow(U256.unsafe(256)) is None

    U256.MaxValue.pow(U256.Zero) is Some(U256.One)
    U256.MaxValue.pow(U256.One) is Some(U256.MaxValue)
    U256.MaxValue.pow(U256.Two) is None
    U256.MaxValue.pow(U256.MaxValue) is None

    val number0 = U256.unsafe(BigInteger.ONE.shiftLeft(128))
    number0.pow(U256.Zero) is Some(U256.One)
    number0.pow(U256.One) is Some(number0)
    number0.pow(U256.Two) is None

    val number1 = U256.unsafe(BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE))
    number1.pow(U256.Zero) is Some(U256.One)
    number1.pow(U256.One) is Some(number1)
    number1.pow(U256.Two) is Some(
      U256.unsafe(
        U256.upperBound.add(BigInteger.ONE).subtract(BigInteger.ONE.shiftLeft(129))
      )
    )

    forAll(Gen.choose(0, 10), Gen.choose(0, 50)) { case (base, exp) =>
      U256.unsafe(base).pow(U256.unsafe(exp)) is Some(
        U256.unsafe(BigInteger.valueOf(base.toLong).pow(exp))
      )
    }

    forAll(Gen.choose(2, 10), Gen.choose(256, 500)) { case (base, exp) =>
      U256.unsafe(base).pow(U256.unsafe(exp)) is None
    }
  }

  it should "test byte length" in {
    (0 until 256).foreach { n =>
      U256.unsafe(BigInteger.TWO.pow(n)).byteLength() is (n / 8 + 1)
    }
    U256.Zero.byteLength() is 0
    U256.MaxValue.byteLength() is 32
  }

  it should "test mod_pow" in {
    U256.Zero.modPow(U256.Zero) is (U256.One)
    U256.Zero.modPow(U256.One) is (U256.Zero)
    U256.Zero.modPow(U256.MaxValue) is (U256.Zero)

    U256.One.modPow(U256.Zero) is (U256.One)
    U256.One.modPow(U256.One) is (U256.One)
    U256.One.modPow(U256.MaxValue) is (U256.One)

    U256.Two.modPow(U256.Zero) is U256.One
    U256.Two.modPow(U256.One) is U256.Two
    U256.Two.modPow(U256.unsafe(255)) is U256.HalfMaxValue.addOneUnsafe()
    U256.Two.modPow(U256.unsafe(256)) is U256.Zero

    U256.MaxValue.modPow(U256.Zero) is U256.One
    U256.MaxValue.modPow(U256.One) is U256.MaxValue
    U256.MaxValue.modPow(U256.Two) is U256.One
    U256.MaxValue.modPow(U256.unsafe(3)) is U256.MaxValue
    U256.MaxValue.modPow(U256.MaxValue) is U256.MaxValue
    U256.MaxValue.modPow(U256.MaxValue).v is
      U256.MaxValue.v.modPow(U256.MaxValue.v, U256.upperBound)

    val number = U256.unsafe(BigInteger.ONE.shiftLeft(255)).addOneUnsafe()
    number.modPow(U256.Two) is U256.One
    number.v.modPow(BigInteger.TWO, U256.upperBound) is BigInteger.ONE
    number.modPow(U256.unsafe(3)) is number
    number.v.modPow(BigInteger.valueOf(3), U256.upperBound) is number.v

    forAll(Gen.choose(0, 10), Gen.choose(0, 200)) { case (base, exp) =>
      U256
        .unsafe(base)
        .modPow(U256.unsafe(exp))
        .v is BigInteger.valueOf(base.toLong).pow(exp).mod(U256.upperBound)
    }
  }

  it should "test mul_mod_n" in {
    U256.Zero.mulModN(U256.Zero, U256.Zero) is None
    U256.One.mulModN(U256.One, U256.Zero) is None
    U256.Zero.mulModN(U256.One, U256.One) is Some(U256.Zero)
    U256.One.mulModN(U256.Zero, U256.One) is Some(U256.Zero)

    U256.One.mulModN(U256.One, U256.One) is Some(U256.Zero)
    U256.One.mulModN(U256.Ten, U256.Two) is Some(U256.Zero)
    U256.Ten.mulModN(U256.One, U256.unsafe(4)) is Some(U256.Two)

    U256.Two.mulModN(U256.Two, U256.MaxValue) is Some(U256.unsafe(4))
    U256.Two.mulModN(U256.MaxValue, U256.MaxValue) is Some(U256.Zero)
    U256.MaxValue.mulModN(U256.Two, U256.MaxValue) is Some(U256.Zero)
    U256.MaxValue.mulModN(U256.MaxValue, U256.MaxValue) is Some(U256.Zero)
    U256.MaxValue.mulModN(U256.MaxValue, U256.MaxValue.subUnsafe(U256.One)) is Some(U256.One)

    val oneShift128 = U256.unsafe(BigInteger.ONE.shiftLeft(128))
    oneShift128.mulModN(oneShift128, U256.MaxValue) is Some(U256.One)
    val u256Gen0 = Gen.choose(BigInteger.ONE, oneShift128.subOneUnsafe().v).map(U256.unsafe)
    forAll(u256Gen0, u256Gen0) { case (x, y) =>
      x.mulModN(y, U256.MaxValue) is Some(U256.unsafe(x.v.multiply(y.v)))
    }

    val u256Gen1 = Gen.choose(BigInteger.ONE, U256.MaxValue.v).map(U256.unsafe)
    forAll(u256Gen1, u256Gen1, u256Gen1) { case (x, y, n) =>
      x.mulModN(y, n) is Some(U256.unsafe(x.v.multiply(y.v).remainder(n.v)))
    }
  }

  it should "test add_mod_n" in {
    U256.Zero.addModN(U256.Zero, U256.Zero) is None
    U256.One.addModN(U256.One, U256.Zero) is None
    U256.Zero.addModN(U256.One, U256.One) is Some(U256.Zero)
    U256.One.addModN(U256.Zero, U256.One) is Some(U256.Zero)

    U256.Two.addModN(U256.Two, U256.MaxValue) is Some(U256.unsafe(4))
    U256.Two.addModN(U256.MaxValue, U256.MaxValue) is Some(U256.Two)
    U256.MaxValue.addModN(U256.Two, U256.MaxValue) is Some(U256.Two)
    U256.MaxValue.addModN(U256.MaxValue, U256.MaxValue) is Some(U256.Zero)
    U256.MaxValue.addModN(U256.MaxValue, U256.MaxValue.subUnsafe(U256.One)) is Some(U256.Two)

    val halfUpperBound = U256.unsafe(BigInteger.ONE.shiftLeft(255))
    halfUpperBound.addModN(halfUpperBound, U256.MaxValue) is Some(U256.One)
    val u256Gen0 = Gen.choose(BigInteger.ONE, halfUpperBound.subOneUnsafe().v).map(U256.unsafe)
    forAll(u256Gen0, u256Gen0) { case (x, y) =>
      x.addModN(y, U256.MaxValue) is Some(U256.unsafe(x.v.add(y.v)))
    }

    val u256Gen1 = Gen.choose(BigInteger.ONE, U256.MaxValue.v).map(U256.unsafe)
    forAll(u256Gen1, u256Gen1, u256Gen1) { case (x, y, n) =>
      x.addModN(y, n) is Some(U256.unsafe(x.v.add(y.v).remainder(n.v)))
    }
  }
}
