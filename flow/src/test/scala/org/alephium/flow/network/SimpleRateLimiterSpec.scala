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

package org.alephium.flow.network

import org.alephium.util.{AlephiumSpec, Duration}

class SimpleRateLimiterSpec extends AlephiumSpec {
  it should "allows requests within limit" in {
    val limiter = SimpleRateLimiter(maxRequests = 5, windowSize = Duration.ofSecondsUnsafe(2))
    limiter.tryRequest(1) is true
    limiter.tryRequest(2) is true
    limiter.tryRequest(2) is true
    limiter.tryRequest(1) is false
  }

  it should "resets after time window" in {
    val limiter = SimpleRateLimiter(maxRequests = 3, windowSize = Duration.ofMillisUnsafe(500))
    limiter.tryRequest(2) is true
    limiter.tryRequest(2) is false
    Thread.sleep(800)
    limiter.tryRequest(2) is true
  }

  it should "allows immediate retry after reset()" in {
    val limiter = SimpleRateLimiter(maxRequests = 2, windowSize = Duration.ofSecondsUnsafe(2))
    limiter.tryRequest(2) is true
    limiter.tryRequest(1) is false
    limiter.clear()
    limiter.tryRequest(1) is true
  }
}
