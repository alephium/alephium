package org.alephium.util

import java.math.BigInteger

object Numeric {
  def isPositive(n: BigInteger): Boolean  = n.signum() > 0
  def nonNegative(n: BigInteger): Boolean = n.signum() >= 0
  def isNegative(n: BigInteger): Boolean  = n.signum() < 0
  def nonPositive(n: BigInteger): Boolean = n.signum() <= 0
}
