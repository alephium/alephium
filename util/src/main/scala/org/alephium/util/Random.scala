package org.alephium.util

import java.security.SecureRandom

object Random {
  val source: SecureRandom = SecureRandom.getInstanceStrong
}
