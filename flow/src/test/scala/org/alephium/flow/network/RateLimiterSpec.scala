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

import org.alephium.util.{AlephiumSpec, Duration, TimeStamp}

class FixedWindowRateLimiterSpec extends AlephiumSpec {
  it should "allow requests within the limit" in new Fixture {
    limiter.tryRequest(1) is true
    limiter.tryRequest(2) is true
    limiter.tryRequest(2) is true
    limiter.tryRequest(1) is false
  }

  it should "reject negative and overflowing request sizes" in new Fixture {
    limiter.tryRequest(-1) is false
    limiter.tryRequest(4) is true
    limiter.tryRequest(Int.MaxValue) is false
    limiter.tryRequest(1) is true
    limiter.tryRequest(1) is false
  }

  it should "reset the entire window at the fixed boundary" in new Fixture {
    advance(Duration.ofMillisUnsafe(800))
    limiter.tryRequest(5) is true
    limiter.tryRequest(1) is false

    advance(Duration.ofMillisUnsafe(200))
    limiter.tryRequest(5) is true
    limiter.tryRequest(1) is false
  }

  it should "tell when the fixed window will reset" in new Fixture {
    limiter.timeUntilAvailable(5).contains(Duration.zero) is true
    limiter.timeUntilAvailable(6) is None
    limiter.timeUntilAvailable(-1) is None

    limiter.tryRequest(2) is true
    advance(Duration.ofMillisUnsafe(400))
    limiter.tryRequest(3) is true
    limiter.timeUntilAvailable(1).contains(Duration.ofMillisUnsafe(600)) is true
    limiter.timeUntilAvailable(3).contains(Duration.ofMillisUnsafe(600)) is true
    limiter.timeUntilAvailable(5).contains(Duration.ofMillisUnsafe(600)) is true

    advance(Duration.ofMillisUnsafe(600))
    limiter.timeUntilAvailable(5).contains(Duration.zero) is true
  }

  it should "start a new fixed window after clear()" in new Fixture {
    advance(Duration.ofMillisUnsafe(400))
    limiter.clear()
    limiter.tryRequest(5) is true

    advance(Duration.ofMillisUnsafe(600))
    limiter.timeUntilAvailable(1).contains(Duration.ofMillisUnsafe(400)) is true
  }

  trait Fixture {
    var now: TimeStamp       = TimeStamp.unsafe(1_000_000)
    val windowSize: Duration = Duration.ofMillisUnsafe(1000)
    val limiter: RateLimiter = FixedWindowRateLimiter(5, windowSize, () => now)

    def advance(duration: Duration): Unit = {
      now = now.plusUnsafe(duration)
    }
  }
}

class SlidingWindowRateLimiterSpec extends AlephiumSpec {
  it should "allow requests within the limit" in new Fixture {
    limiter.tryRequest(1) is true
    limiter.tryRequest(2) is true
    limiter.tryRequest(2) is true
    limiter.tryRequest(1) is false
  }

  it should "reject negative and overflowing request sizes" in new Fixture {
    limiter.tryRequest(-1) is false
    limiter.tryRequest(4) is true
    limiter.tryRequest(Int.MaxValue) is false
    limiter.tryRequest(1) is true
    limiter.tryRequest(1) is false
  }

  it should "expire the oldest requests instead of resetting the whole window" in new Fixture {
    limiter.tryRequest(4) is true
    advance(Duration.ofMillisUnsafe(400))
    limiter.tryRequest(1) is true
    limiter.tryRequest(1) is false

    advance(Duration.ofMillisUnsafe(600))
    limiter.tryRequest(4) is true
    limiter.tryRequest(1) is false

    advance(Duration.ofMillisUnsafe(400))
    limiter.tryRequest(1) is true
    limiter.tryRequest(1) is false
  }

  it should "tell when enough sliding-window capacity will be available" in new Fixture {
    limiter.timeUntilAvailable(5).contains(Duration.zero) is true
    limiter.timeUntilAvailable(6) is None
    limiter.timeUntilAvailable(-1) is None

    limiter.tryRequest(2) is true
    advance(Duration.ofMillisUnsafe(400))
    limiter.tryRequest(3) is true
    limiter.timeUntilAvailable(1).contains(Duration.ofMillisUnsafe(600)) is true
    limiter.timeUntilAvailable(3).contains(Duration.ofMillisUnsafe(1000)) is true
    limiter.timeUntilAvailable(5).contains(Duration.ofMillisUnsafe(1000)) is true
  }

  it should "allow an immediate retry after clear()" in new Fixture {
    limiter.tryRequest(5) is true
    limiter.tryRequest(1) is false
    limiter.clear()
    limiter.tryRequest(1) is true
    limiter.timeUntilAvailable(4).contains(Duration.zero) is true
  }

  trait Fixture {
    var now: TimeStamp       = TimeStamp.unsafe(1_000_000)
    val windowSize: Duration = Duration.ofMillisUnsafe(1000)
    val limiter: RateLimiter = SlidingWindowRateLimiter(5, windowSize, () => now)

    def advance(duration: Duration): Unit = {
      now = now.plusUnsafe(duration)
    }
  }
}
