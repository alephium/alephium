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

import scala.language.implicitConversions
import scala.math.BigInt
import scala.util.Random

import org.scalacheck.Gen

trait NumericHelpers {
  lazy val smallPositiveInt: Gen[Int] = Gen.choose(1, 5)

  implicit class U256Helpers(n: U256) { Self =>
    def +(m: U256): U256 = n.add(m).get
    def -(m: U256): U256 = n.sub(m).get
    def *(m: U256): U256 = n.mul(m).get
    def /(m: U256): U256 = n.div(m).get
  }

  implicit def intToU256(n: Int): U256   = U256.unsafe(n.toLong)
  implicit def longToU256(n: Long): U256 = U256.unsafe(n)

  implicit def bigInt(n: BigInteger): BigInt = BigInt(n)

  private val random = Random.self

  // Generate a random Int in the range of [from, to]
  def nextInt(from: Int, to: Int): Int = random.nextInt(to - from + 1) + from
  def nextInt(to: Int): Int            = nextInt(0, to)
  def nextLong(from: Long, to: Long): Long = {
    if (from equals to) from else random.nextLong() % (to - from) + from
  }
  def nextLong(to: Long): Long = nextLong(0, to)
  def nextU256(from: U256, to: U256): U256 = {
    val range = (to - from).toBigInt
    var shift = new BigInteger(range.bitLength(), random)
    while (shift.compareTo(range) > 0) {
      shift = new BigInteger(range.bitLength(), random)
    }
    from + U256.from(shift).get
  }
  def nextU256(to: U256): U256 = nextU256(U256.Zero, to)

  def min(xs: U256*): U256 = xs.min
  def max(xs: U256*): U256 = xs.max
}

class NumericHelpersSpec extends AlephiumSpec with NumericHelpers {
  it should "generate random int" in {
    nextInt(0, 0) is 0
    nextInt(Int.MaxValue, Int.MaxValue) is Int.MaxValue
    nextInt(Int.MinValue, Int.MinValue) is Int.MinValue
    nextLong(0, 0) is 0
    nextLong(Int.MaxValue, Int.MaxValue) is Int.MaxValue
    nextLong(Int.MinValue, Int.MinValue) is Int.MinValue
    nextU256(U256.Zero, U256.Zero) is U256.Zero
    nextU256(U256.MaxValue, U256.MaxValue) is U256.MaxValue
    nextU256(U256.One, U256.MaxValue).isZero is false
  }

  it should "find min/max value in the list" in {
    forAll(Gen.nonEmptyListOf(posLongGen)) { ns: Seq[Long] =>
      min(ns.map(n => U256.unsafe(n)): _*) is ns.min
      max(ns.map(n => U256.unsafe(n)): _*) is ns.max
    }
  }
}
