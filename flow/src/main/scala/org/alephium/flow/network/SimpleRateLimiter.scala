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

import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.model.NetworkId
import org.alephium.util.{Duration, TimeStamp}

final class SimpleRateLimiter(maxRequests: Int, windowSize: Duration) {
  private var _requestCount = 0
  private var _windowStart  = TimeStamp.now()

  def tryRequest(size: Int): Boolean = {
    val now = TimeStamp.now()
    now -- _windowStart match {
      case Some(diff) =>
        if (diff.millis >= windowSize.millis) {
          _requestCount = 0
          _windowStart = now
        }
      case None =>
    }

    if (_requestCount + size <= maxRequests) {
      _requestCount += size
      true
    } else {
      false
    }
  }

  def clear(): Unit = {
    _requestCount = 0
    _windowStart = TimeStamp.now()
  }
}

object SimpleRateLimiter {
  def apply(maxRequests: Int, windowSize: Duration): SimpleRateLimiter =
    new SimpleRateLimiter(maxRequests, windowSize)

  def default(implicit networkSetting: NetworkSetting): SimpleRateLimiter = {
    val windowSize = if (networkSetting.networkId == NetworkId.AlephiumMainNet) {
      RateLimiterWindowSizeMainnet
    } else {
      RateLimiterWindowSize
    }
    SimpleRateLimiter(MaxRequestNum, windowSize)
  }
}
