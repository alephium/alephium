package org.alephium.util

import java.math.BigInteger
import java.security.SecureRandom

import scala.language.implicitConversions

import org.scalacheck.Gen

trait NumericHelpers {
  lazy val smallPositiveInt: Gen[Int] = Gen.choose(1, 5)

  implicit class U64Helpers(n: U64) { Self =>
    def +(m: U64): U64 = n.add(m).get
    def -(m: U64): U64 = n.sub(m).get
    def *(m: U64): U64 = n.mul(m).get
    def /(m: U64): U64 = n.div(m).get
  }

  implicit def intToU64(n: Int): U64   = U64.unsafe(n.toLong)
  implicit def longToU64(n: Long): U64 = U64.unsafe(n)

  private val random = SecureRandom.getInstanceStrong

  // Generate a random Int in the range of [from, to]
  def nextInt(from: Int, to: Int): Int = random.nextInt(to - from + 1) + from
  def nextInt(to: Int): Int            = nextInt(0, to)
  def nextLong(from: Long, to: Long): Long = {
    if (from equals to) from else (random.nextLong() % (to - from) + from)
  }
  def nextLong(to: Long): Long = nextLong(0, to)
  def nextU64(from: U64, to: U64): U64 = {
    val range = (to - from).toBigInt
    var shift = new BigInteger(range.bitLength(), random)
    while (shift.compareTo(range) > 0) {
      shift = new BigInteger(range.bitLength(), random)
    }
    from + U64.from(shift).get
  }
  def nextU64(to: U64): U64 = nextU64(U64.Zero, to)

  def min(xs: U64*): U64 = xs.min
  def max(xs: U64*): U64 = xs.max
}

class NumericHelpersSpec extends AlephiumSpec with NumericHelpers {
  it should "generate random int" in {
    nextInt(0, 0) is 0
    nextInt(Int.MaxValue, Int.MaxValue) is Int.MaxValue
    nextInt(Int.MinValue, Int.MinValue) is Int.MinValue
    nextLong(0, 0) is 0
    nextLong(Int.MaxValue, Int.MaxValue) is Int.MaxValue
    nextLong(Int.MinValue, Int.MinValue) is Int.MinValue
    nextU64(U64.Zero, U64.Zero) is U64.Zero
    nextU64(U64.MaxValue, U64.MaxValue) is U64.MaxValue
    nextU64(U64.One, U64.MaxValue).isZero is false
  }

  it should "find min/max value in the list" in {
    forAll(Gen.nonEmptyListOf(Gen.posNum[Int])) { ns: Seq[Int] =>
      min(ns.map(n => U64.unsafe(n.toLong)): _*) is ns.min
      max(ns.map(n => U64.unsafe(n.toLong)): _*) is ns.max
    }
  }
}
