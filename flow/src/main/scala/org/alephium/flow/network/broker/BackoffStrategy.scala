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

import org.alephium.flow.setting.NetworkSetting
import org.alephium.util.{Duration, Math, TimeStamp}

trait BackoffStrategy {
  def retry(f: Duration => Unit): Boolean
}

class DefaultBackoffStrategy(network: NetworkSetting) extends BackoffStrategy {
  def baseDelay: Duration       = network.backoffBaseDelay
  def maxDelay: Duration        = network.backoffMaxDelay
  def maxRetry: Int             = 8
  protected var retryCount: Int = 0
  protected def backoff         = Math.min(baseDelay.timesUnsafe(1L << retryCount), maxDelay)

  def retry(f: Duration => Unit): Boolean = {
    if (retryCount < maxRetry) {
      f(backoff)
      retryCount += 1
      true
    } else {
      false
    }
  }
}

object DefaultBackoffStrategy {
  def apply()(implicit network: NetworkSetting): DefaultBackoffStrategy =
    new DefaultBackoffStrategy(network)
}

class ResetBackoffStrategy(network: NetworkSetting) extends DefaultBackoffStrategy(network) {
  var lastAccess: TimeStamp = TimeStamp.now()
  val resetDelay: Duration  = network.backoffResetDelay

  override def retry(f: Duration => Unit): Boolean = {
    if (retryCount == maxRetry) {
      resetCount()
    }
    val retried = super.retry(f)
    if (retried) {
      lastAccess = TimeStamp.now()
    }
    retried
  }

  private def resetCount(): Unit = {
    val elapsedTime = TimeStamp.now().deltaUnsafe(lastAccess)
    if (elapsedTime >= resetDelay) {
      retryCount = 0
      lastAccess = TimeStamp.now()
    }
  }
}

object ResetBackoffStrategy {
  def apply()(implicit network: NetworkSetting): ResetBackoffStrategy =
    new ResetBackoffStrategy(network)
}
