package org.alephium.constant

import scala.concurrent.duration._
import scala.language.postfixOps

object Network {
  val port: Int                     = 9973
  val pingFrequency: FiniteDuration = 5 minute
}
