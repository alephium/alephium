package org.alephium.util

import java.math.BigInteger

class NumericSpec extends AlephiumSpec {
  it should "check positivity of big integer" in {
    Numeric.isPositive(BigInteger.ONE) is true
    Numeric.nonPositive(BigInteger.ONE) is false
    Numeric.isNegative(BigInteger.ONE) is false
    Numeric.nonNegative(BigInteger.ONE) is true
    Numeric.isPositive(BigInteger.ZERO) is false
    Numeric.nonPositive(BigInteger.ZERO) is true
    Numeric.isNegative(BigInteger.ZERO) is false
    Numeric.nonNegative(BigInteger.ZERO) is true
    Numeric.isPositive(BigInteger.ONE.negate()) is false
    Numeric.nonPositive(BigInteger.ONE.negate()) is true
    Numeric.isNegative(BigInteger.ONE.negate()) is true
    Numeric.nonNegative(BigInteger.ONE.negate()) is false
  }
}
