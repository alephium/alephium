package org.alephium.util

class TimeStamp(val millis: Long) extends AnyVal with Ordered[TimeStamp] {
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

  def +(duration: Duration): TimeStamp =
    TimeStamp.unsafe(millis + duration.millis)

  def -(duration: Duration): Option[TimeStamp] =
    TimeStamp.from(millis - duration.millis)

  def --(another: TimeStamp): Option[Duration] =
    Duration.from(millis - another.millis)

  def isBefore(another: TimeStamp): Boolean =
    millis < another.millis

  def compare(that: TimeStamp): Int = millis compare that.millis
}

object TimeStamp {
  def unsafe(millis: Long): TimeStamp = {
    assume(millis >= 0, "timestamp should be non-negative")
    new TimeStamp(millis)
  }

  def from(millis: Long): Option[TimeStamp] = {
    if (millis >= 0) Some(new TimeStamp(millis)) else None
  }

  val zero: TimeStamp = unsafe(0)

  def now(): TimeStamp = unsafe(System.currentTimeMillis())
}
