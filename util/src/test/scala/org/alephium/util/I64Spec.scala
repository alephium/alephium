package org.alephium.util

import java.math.BigInteger

class I64Spec extends AlephiumSpec {
  val numGen = (0L to 4L).flatMap(i => List(i - 2, Long.MinValue + i, Long.MaxValue - i))

  it should "convert to BigInt" in {
    I64.Zero.toBigInt is BigInteger.ZERO
    I64.One.toBigInt is BigInteger.ONE
    I64.Two.toBigInt is BigInteger.TWO
    I64.NegOne.toBigInt is BigInteger.ONE.negate()
    I64.MinValue.toBigInt is BigInteger.TWO.pow(63).negate()
    I64.MaxValue.toBigInt is BigInteger.TWO.pow(63).subtract(BigInteger.ONE)
  }

  it should "convert from BigInt" in {
    I64.from(I64.MinValue.toBigInt).get is I64.MinValue
    I64.from(I64.MaxValue.toBigInt).get is I64.MaxValue
    I64.from(I64.MinValue.toBigInt.subtract(BigInteger.ONE)).isEmpty is true
    I64.from(I64.MaxValue.toBigInt.add(BigInteger.ONE)).isEmpty is true
  }

  def test(op: (I64, I64)                       => Option[I64],
           opUnsafe: (I64, I64)                 => I64,
           opExpected: (BigInteger, BigInteger) => BigInteger,
           bcondition: Long                     => Boolean = _ >= Long.MinValue,
           abcondition: (Long, Long)            => Boolean = _ + _ >= Long.MinValue): Unit = {
    for {
      a <- numGen
      b <- numGen
    } {
      val aI64          = I64.from(a)
      val bI64          = I64.from(b)
      lazy val expected = opExpected(aI64.toBigInt, bI64.toBigInt)
      if (bcondition(b) && abcondition(a, b) && expected >= I64.MinValue.toBigInt && expected <= I64.MaxValue.toBigInt) {
        op(aI64, bI64).get.toBigInt is expected
        opUnsafe(aI64, bI64).toBigInt is expected
      } else {
        assertThrows[AssertionError](opUnsafe(aI64, bI64))
        op(aI64, bI64).isEmpty is true
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
    test(_.div(_), _.divUnsafe(_), _.divide(_), _ != 0, _ != Long.MinValue || _ != -1)
  }

  it should "test mod" in {
    test(_.mod(_), _.modUnsafe(_), _.remainder(_), _ != 0)
  }

  it should "compare I64" in {
    for {
      a <- numGen
      b <- numGen
    } {
      val aI64 = I64.from(a)
      val bI64 = I64.from(b)
      aI64.compareTo(bI64) is java.lang.Long.compare(a, b)
    }
  }
}
