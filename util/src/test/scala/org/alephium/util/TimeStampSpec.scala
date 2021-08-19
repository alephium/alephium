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

package org.alephium.util

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalacheck.Gen
import org.scalatest.Assertion

class TimeStampSpec extends AlephiumSpec {
  it should "initialize correctly" in {
    TimeStamp.from(1).get.millis is 1
    (TimeStamp.now().millis > 0) is true

    TimeStamp.from(-1).isEmpty is true
  }

  it should "get current millisecond" in {
    val ts  = TimeStamp.now()
    val sys = System.currentTimeMillis()
    (ts.millis + 1000 >= sys) is true
  }

  it should "update correctly" in {
    def check(ts: TimeStamp, it: Instant): Assertion = {
      ts.millis is it.toEpochMilli
    }

    val instant = Instant.now()
    val ts      = TimeStamp.unsafe(instant.toEpochMilli)

    check(ts.plusMillis(100).get, instant.plusMillis(100))
    check(ts.plusMillisUnsafe(100), instant.plusMillis(100))
    check(ts.plusSeconds(100).get, instant.plusSeconds(100))
    check(ts.plusSecondsUnsafe(100), instant.plusSeconds(100))
    check(ts.plusMinutes(100).get, instant.plus(100, ChronoUnit.MINUTES))
    check(ts.plusMinutesUnsafe(100), instant.plus(100, ChronoUnit.MINUTES))
    check(ts.plusHours(100).get, instant.plus(100, ChronoUnit.HOURS))
    check(ts.plusHoursUnsafe(100), instant.plus(100, ChronoUnit.HOURS))
    ts.plusMillis(-2 * ts.millis).isEmpty is true
    assertThrows[AssertionError](ts.plusMillisUnsafe(-2 * ts.millis))
  }

  it should "operate correctly" in {
    forAll(Gen.chooseNum(0, Long.MaxValue)) { l0 =>
      forAll(Gen.chooseNum(0, l0)) { l1 =>
        val ts = TimeStamp.unsafe(l0 / 2)
        val dt = Duration.unsafe(l1 / 2)
        (ts + dt).millis is ts.millis + dt.millis
        (ts - dt).get.millis is ts.millis - dt.millis
        (ts minusUnsafe dt).millis is ts.millis - dt.millis

        val ts0 = TimeStamp.unsafe(l0)
        val ts1 = TimeStamp.unsafe(l1)
        if (ts0 >= ts1) {
          (ts0 -- ts1).get.millis is l0 - l1
        } else {
          (ts0 -- ts1) is None
        }
        if (l0 != l1) {
          ts0 isBefore ts1 is false
          ts1 isBefore ts0 is true
        } else {
          ts0 isBefore ts1 is false
          ts1 isBefore ts0 is false
        }
      }
    }
  }

  it should "plus" in {
    val t0 = TimeStamp.zero
    val t1 = TimeStamp.unsafe(1)
    val d0 = Duration.unsafe(Long.MaxValue - 1)
    val d1 = Duration.unsafe(Long.MaxValue)
    t0.plus(d0).get.millis is d0.millis
    t0.plus(d1).get.millis is d1.millis
    t1.plus(d0).get.millis is d1.millis
    t1.plus(d1) is None
  }
}
