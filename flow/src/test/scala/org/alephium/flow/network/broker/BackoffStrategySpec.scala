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

import org.scalatest.concurrent.Eventually

import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.util.{discard, AlephiumSpec, Duration, Math}

class BackoffStrategySpec extends AlephiumSpec with AlephiumConfigFixture {
  implicit lazy val network                    = networkConfig
  def createStrategy(): DefaultBackoffStrategy = DefaultBackoffStrategy()

  it should "calculate the correct delay" in new DefaultFixture {
    var backoff = strategy.baseDelay.divUnsafe(2)
    def test(expected: Duration) = {
      strategy.retry(backoff = _) is true
      backoff is expected
    }

    (0 until strategy.maxRetry).foreach { _ =>
      val expected =
        Math.min(backoff.timesUnsafe(2), strategy.maxDelay)
      test(expected)
    }
  }

  it should "not retry more than 1 minute" in new DefaultFixture {
    var total = Duration.zero
    (0 until strategy.maxRetry).foreach { _ =>
      strategy.retry(backoff => total = total + backoff)
    }
    (total < Duration.ofMinutesUnsafe(1)) is true
  }

  trait DefaultFixture {
    val strategy: DefaultBackoffStrategy = createStrategy()
  }
}

class ResetBackoffStrategySpec extends BackoffStrategySpec {
  override def createStrategy(): ResetBackoffStrategy = ResetBackoffStrategy()
  override val configValues: Map[String, Any] = Map(
    "alephium.network.backoff-reset-delay" -> "500 milli"
  )

  trait ResetFixture extends Eventually {
    val strategy: ResetBackoffStrategy = createStrategy()
  }

  it should "correctly reset the counter" in new ResetFixture {
    (0 until strategy.maxRetry).foreach { _ => strategy.retry(discard) is true }
    strategy.retry(discard) is false
    eventually(strategy.retry(discard) is true)
  }
}
