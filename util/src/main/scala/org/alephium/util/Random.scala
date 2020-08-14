package org.alephium.util

import java.security.SecureRandom

import scala.annotation.tailrec

object Random {
  val source: SecureRandom = SecureRandom.getInstanceStrong

  @tailrec
  def nextNonZeroInt(): Int = {
    val random = source.nextInt()
    if (random != 0) random else nextNonZeroInt()
  }
}
