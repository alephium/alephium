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

import java.time.{Duration => JDuration}

import scala.concurrent.duration.{FiniteDuration => SDuration, MILLISECONDS}

// Note: millis should always be positive
class Duration(val millis: Long) extends AnyVal with Ordered[Duration] {
  def toSeconds: Long = {
    val seconds   = millis / 1000
    val remainder = millis % 1000
    if (remainder < 0) seconds - 1 else seconds
  }

  def toMinutes: Long = toSeconds / 60

  def toHours: Long = toSeconds / (60 * 60)

  // Note: if this is not in the bound of scala Duration, the method will throw an exception
  // Scala Duration is limited to +-(2^63-1)ns (ca. 292 years)
  def asScala: SDuration = SDuration.apply(millis, MILLISECONDS)

  def +(another: Duration): Duration = Duration.unsafe(millis + another.millis)

  def -(another: Duration): Option[Duration] = Duration.from(millis - another.millis)

  def times(scale: Long): Option[Duration] = Duration.from(millis * scale)
  def timesUnsafe(scale: Long): Duration   = Duration.unsafe(millis * scale)
  def *(scale: Long): Option[Duration]     = times(scale)

  def div(scale: Long): Option[Duration] = Duration.from(millis / scale)
  def divUnsafe(scale: Long): Duration   = Duration.unsafe(millis / scale)
  def /(scale: Long): Option[Duration]   = Duration.from(millis / scale)

  def compare(that: Duration): Int = millis compare that.millis

  override def toString: String = s"${millis}ms"
}

object Duration {
  def unsafe(millis: Long): Duration = {
    assume(millis >= 0, "duration should be positive")
    new Duration(millis)
  }

  def from(millis: Long): Option[Duration] = {
    if (millis >= 0) Some(new Duration(millis)) else None
  }

  val zero: Duration = unsafe(0)

  def ofMillis(millis: Long): Option[Duration] = from(millis)
  def ofMillisUnsafe(millis: Long): Duration   = unsafe(millis)

  def ofSeconds(seconds: Long): Option[Duration] = from(seconds * 1000)
  def ofSecondsUnsafe(seconds: Long): Duration   = unsafe(seconds * 1000)

  def ofMinutes(minutes: Long): Option[Duration] = from(minutes * 60 * 1000)
  def ofMinutesUnsafe(minutes: Long): Duration   = unsafe(minutes * 60 * 1000)

  def ofHours(hours: Long): Option[Duration] = from(hours * 60 * 60 * 1000)
  def ofHoursUnsafe(hours: Long): Duration   = unsafe(hours * 60 * 60 * 1000)

  def from(dt: JDuration): Option[Duration] = ofMillis(dt.toMillis)
}
