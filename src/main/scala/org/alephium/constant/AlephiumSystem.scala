package org.alephium.constant

import akka.util.Timeout

import scala.concurrent.duration._

object AlephiumSystem {
  implicit val akkaAskTimeOut = Timeout(5.seconds)
}
