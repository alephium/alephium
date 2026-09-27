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

import scala.collection.mutable

import org.alephium.util.{Duration, TimeStamp}

sealed trait RateLimiter {
  def tryRequest(size: Int): Boolean

  def timeUntilAvailable(size: Int): Option[Duration]

  def clear(): Unit
}

final class FixedWindowRateLimiter(
    maxRequests: Int,
    windowSize: Duration,
    clock: () => TimeStamp
) extends RateLimiter {
  private var requestCount = 0L
  private var windowStart  = clock()

  override def tryRequest(size: Int): Boolean = {
    if (size < 0) {
      false
    } else {
      val now = clock()
      resetExpiredWindow(now)
      val updatedRequestCount = requestCount + size.toLong
      if (updatedRequestCount <= maxRequests.toLong) {
        requestCount = updatedRequestCount
        true
      } else {
        false
      }
    }
  }

  override def timeUntilAvailable(size: Int): Option[Duration] = {
    if (size < 0 || size > maxRequests) {
      None
    } else {
      val now = clock()
      resetExpiredWindow(now)
      if (requestCount + size.toLong <= maxRequests.toLong) {
        Some(Duration.zero)
      } else {
        Some(timeUntilWindowReset(now))
      }
    }
  }

  override def clear(): Unit = {
    requestCount = 0L
    windowStart = clock()
  }

  private def resetExpiredWindow(now: TimeStamp): Unit = {
    now -- windowStart match {
      case Some(diff) if diff.millis >= windowSize.millis =>
        requestCount = 0L
        windowStart = now
      case _ => ()
    }
  }

  private def timeUntilWindowReset(now: TimeStamp): Duration = {
    windowStart.plusUnsafe(windowSize) -- now match {
      case Some(wait) => wait
      case None       => Duration.zero
    }
  }
}

object FixedWindowRateLimiter {
  def apply(maxRequests: Int, windowSize: Duration): FixedWindowRateLimiter = {
    apply(maxRequests, windowSize, () => TimeStamp.now())
  }

  def apply(
      maxRequests: Int,
      windowSize: Duration,
      clock: () => TimeStamp
  ): FixedWindowRateLimiter = {
    new FixedWindowRateLimiter(maxRequests, windowSize, clock)
  }
}

final class SlidingWindowRateLimiter(
    maxRequests: Int,
    windowSize: Duration,
    clock: () => TimeStamp
) extends RateLimiter {
  private val requests     = mutable.Queue.empty[(TimeStamp, Int)]
  private var requestCount = 0L

  override def tryRequest(size: Int): Boolean = {
    if (size < 0) {
      false
    } else {
      val now = clock()
      evictExpired(now)
      val updatedRequestCount = requestCount + size.toLong
      if (updatedRequestCount <= maxRequests.toLong) {
        if (size > 0) {
          requests.enqueue((now, size))
          requestCount = updatedRequestCount
        }
        true
      } else {
        false
      }
    }
  }

  override def timeUntilAvailable(size: Int): Option[Duration] = {
    if (size < 0 || size > maxRequests) {
      None
    } else {
      val now = clock()
      evictExpired(now)
      if (requestCount + size.toLong <= maxRequests.toLong) {
        Some(Duration.zero)
      } else {
        waitUntilCountFits(now, size)
      }
    }
  }

  override def clear(): Unit = {
    requests.clear()
    requestCount = 0L
  }

  @scala.annotation.tailrec
  private def evictExpired(now: TimeStamp): Unit = {
    requests.headOption match {
      case Some((timestamp, size)) if isExpired(now, timestamp) =>
        requests.dequeue()
        requestCount -= size.toLong
        evictExpired(now)
      case _ => ()
    }
  }

  private def isExpired(now: TimeStamp, timestamp: TimeStamp): Boolean = {
    now -- timestamp match {
      case Some(diff) => diff.millis >= windowSize.millis
      case None       => false
    }
  }

  private def waitUntilCountFits(now: TimeStamp, size: Int): Option[Duration] = {
    val needToFree = requestCount + size.toLong - maxRequests.toLong
    requests.iterator
      .scanLeft((0L, Option.empty[TimeStamp])) { case ((acc, _), (timestamp, entrySize)) =>
        (acc + entrySize.toLong, Some(timestamp))
      }
      .collectFirst { case (freed, Some(timestamp)) if freed >= needToFree => timestamp }
      .map { timestamp =>
        timestamp.plusUnsafe(windowSize) -- now match {
          case Some(wait) => wait
          case None       => Duration.zero
        }
      }
  }
}

object SlidingWindowRateLimiter {
  def apply(maxRequests: Int, windowSize: Duration): SlidingWindowRateLimiter = {
    apply(maxRequests, windowSize, () => TimeStamp.now())
  }

  def apply(
      maxRequests: Int,
      windowSize: Duration,
      clock: () => TimeStamp
  ): SlidingWindowRateLimiter = {
    new SlidingWindowRateLimiter(maxRequests, windowSize, clock)
  }
}

/** A sliding-window limiter that also spaces accepted requests over time.
  *
  * The sliding window keeps the hard protocol limit, while pacing prevents an outbound client from
  * consuming the whole window in one burst and then waiting for the window to expire.
  */
final class PacedRateLimiter(
    maxRequests: Int,
    windowSize: Duration,
    clock: () => TimeStamp
) extends RateLimiter {
  require(maxRequests > 0, "maxRequests must be positive")
  require(windowSize > Duration.zero, "windowSize must be positive")

  private val windowLimiter = SlidingWindowRateLimiter(maxRequests, windowSize, clock)
  private var nextRequestAt = clock()

  override def tryRequest(size: Int): Boolean = {
    if (size < 0 || size > maxRequests) {
      false
    } else if (size == 0) {
      true
    } else {
      val now = clock()
      if (now < nextRequestAt || !windowLimiter.tryRequest(size)) {
        false
      } else {
        nextRequestAt = now.plusUnsafe(requestSpacing(size))
        true
      }
    }
  }

  override def timeUntilAvailable(size: Int): Option[Duration] = {
    if (size < 0 || size > maxRequests) {
      None
    } else if (size == 0) {
      Some(Duration.zero)
    } else {
      windowLimiter.timeUntilAvailable(size).map { windowWait =>
        val now = clock()
        val pacingWait = nextRequestAt -- now match {
          case Some(wait) => wait
          case None       => Duration.zero
        }
        if (pacingWait > windowWait) pacingWait else windowWait
      }
    }
  }

  override def clear(): Unit = {
    windowLimiter.clear()
    nextRequestAt = clock()
  }

  private def requestSpacing(size: Int): Duration = {
    val weightedWindow = Math.multiplyExact(size.toLong, windowSize.millis)
    val spacingMillis  = (weightedWindow - 1L) / maxRequests.toLong + 1L
    Duration.ofMillisUnsafe(spacingMillis)
  }
}

object PacedRateLimiter {
  def apply(maxRequests: Int, windowSize: Duration): PacedRateLimiter = {
    apply(maxRequests, windowSize, () => TimeStamp.now())
  }

  def apply(
      maxRequests: Int,
      windowSize: Duration,
      clock: () => TimeStamp
  ): PacedRateLimiter = {
    new PacedRateLimiter(maxRequests, windowSize, clock)
  }
}
