package org.alephium.util

import java.time.{Duration => JDuration}

import scala.concurrent.duration.{FiniteDuration => SDuration, MILLISECONDS}

import org.scalatest.Assertion

class DurationSpec extends AlephiumSpec {
  def check(dt: Duration, jdt: JDuration): Assertion = {
    dt.millis is jdt.toMillis
    dt.toSeconds is jdt.getSeconds
    dt.toMinutes is jdt.toMinutes
    dt.toHours is jdt.toHours

    if (dt.millis <= Long.MaxValue / 2 && dt.millis >= Long.MaxValue / 2) {
      (dt + dt).millis is 2 * dt.millis
      (dt timesUnsafe 2).millis is 2 * dt.millis
    }
    (dt - dt).get.millis is 0
    (dt divUnsafe 2).millis is dt.millis / 2
  }

  it should "initialize correctly" in {
    Duration.zero.millis is 0
    forAll { millis: Long =>
      if (millis >= 0) { // otherwise it will fail in jdk8!
        JDuration.ofMillis(millis).toMillis
        check(Duration.ofMillisUnsafe(millis), JDuration.ofMillis(millis))

        val seconds = millis / 1000
        check(Duration.ofSecondsUnsafe(seconds), JDuration.ofSeconds(seconds))

        val minutes = seconds / 60
        check(Duration.ofMinutesUnsafe(minutes), JDuration.ofMinutes(minutes))

        val hours = minutes / 60
        check(Duration.ofHoursUnsafe(hours), JDuration.ofHours(hours))
      } else {
        assertThrows[AssertionError](Duration.ofMillisUnsafe(millis))
        Duration.ofMillis(millis) is None
      }
    }
  }

  it should "operate correctly" in {
    forAll { (l0: Long, l1: Long) =>
      whenever(l0 >= 0 && l1 >= 0) {
        val dt0 = Duration.ofMillisUnsafe(l0 / 2)
        val dt1 = Duration.ofMillisUnsafe(l1 / 2)
        (dt0 + dt1).millis is dt0.millis + dt1.millis
        if (dt0 > dt1) {
          (dt0 - dt1).get.millis is dt0.millis - dt1.millis
        }
        (dt0 timesUnsafe 2).millis is dt0.millis * 2
        (dt0 divUnsafe 2).millis is dt0.millis / 2

        val maxMS = Long.MaxValue / 1000000
        if (l0 <= maxMS) {
          Duration.ofMillis(l0).get.asScala is SDuration.apply(l0, MILLISECONDS)
        } else {
          assertThrows[IllegalArgumentException](Duration.ofMillis(l0).get.asScala)
        }
      }
    }
  }
}
