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

package org.alephium.flow.network.broker

import org.alephium.util.{Duration, Math}

class BackoffStrategy(var retryCount: Int) {
  import BackoffStrategy._

  def retry(f: Duration => Unit): Boolean = {
    if (retryCount < maxRetry) {
      val backoff = baseDelay.timesUnsafe(1L << retryCount)
      retryCount += 1
      f(Math.min(backoff, maxBackOff))
      true
    } else {
      false
    }
  }
}

object BackoffStrategy {
  def default(): BackoffStrategy = new BackoffStrategy(0)

  // scalastyle:off magic.number
  val baseDelay: Duration  = Duration.ofMillisUnsafe(500)
  val maxBackOff: Duration = Duration.ofSecondsUnsafe(8)
  val maxRetry: Int        = 8
  // scalastyle:on magic.number
}
