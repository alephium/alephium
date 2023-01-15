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

class TimeStamp(val millis: Long) extends AnyVal with Ordered[TimeStamp] {
  def isZero(): Boolean = millis == 0

  def plusMillis(millisToAdd: Long): Option[TimeStamp] =
    TimeStamp.from(millis + millisToAdd)

  def plusMillisUnsafe(millisToAdd: Long): TimeStamp = {
    TimeStamp.unsafe(millis + millisToAdd)
  }

  def plusSeconds(secondsToAdd: Long): Option[TimeStamp] =
    TimeStamp.from(millis + secondsToAdd * 1000)

  def plusSecondsUnsafe(secondsToAdd: Long): TimeStamp = {
    TimeStamp.unsafe(millis + secondsToAdd * 1000)
  }

  def plusMinutes(minutesToAdd: Long): Option[TimeStamp] =
    TimeStamp.from(millis + minutesToAdd * 60 * 1000)

  def plusMinutesUnsafe(minutesToAdd: Long): TimeStamp = {
    TimeStamp.unsafe(millis + minutesToAdd * 60 * 1000)
  }

  def plusHours(hoursToAdd: Long): Option[TimeStamp] =
    TimeStamp.from(millis + hoursToAdd * 60 * 60 * 1000)

  def plusHoursUnsafe(hoursToAdd: Long): TimeStamp =
    TimeStamp.unsafe(millis + hoursToAdd * 60 * 60 * 1000)

  def plus(duration: Duration): Option[TimeStamp] =
    TimeStamp.from(millis + duration.millis)

  def plusUnsafe(duration: Duration): TimeStamp =
    TimeStamp.unsafe(millis + duration.millis)

  def +(duration: Duration): TimeStamp =
    TimeStamp.unsafe(millis + duration.millis)

  def -(duration: Duration): Option[TimeStamp] =
    TimeStamp.from(millis - duration.millis)

  def minusUnsafe(duration: Duration): TimeStamp =
    TimeStamp.unsafe(millis - duration.millis)

  def --(another: TimeStamp): Option[Duration] =
    Duration.from(millis - another.millis)

  def deltaUnsafe(another: TimeStamp): Duration =
    Duration.unsafe(millis - another.millis)

  def isBefore(another: TimeStamp): Boolean =
    millis < another.millis

  def compare(that: TimeStamp): Int = millis compare that.millis

  override def toString: String = s"TimeStamp(${millis}ms)"
}

object TimeStamp {
  implicit val ordering: Ordering[TimeStamp] = (x: TimeStamp, y: TimeStamp) => x.compare(y)

  def unsafe(millis: Long): TimeStamp = {
    assume(millis >= 0, "timestamp should be non-negative")
    new TimeStamp(millis)
  }

  def from(millis: Long): Option[TimeStamp] = {
    if (millis >= 0) Some(new TimeStamp(millis)) else None
  }

  val zero: TimeStamp = unsafe(0)

  val Max: TimeStamp = unsafe(Long.MaxValue)

  def now(): TimeStamp = unsafe(System.currentTimeMillis())
}
