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

package org.alephium.flow

import org.alephium.flow.core.maxSyncBlocksPerChain
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.model.NetworkId
import org.alephium.util.Duration

package object network {
  // scalastyle:off magic.number
  val MaxRequestNum: Int                     = maxSyncBlocksPerChain * 16
  val RateLimiterWindowSizeMainnet: Duration = Duration.ofSecondsUnsafe(30)
  val RateLimiterWindowSize: Duration        = Duration.ofSecondsUnsafe(10)
  // scalastyle:on magic.number

  def getRateLimiterWindowSize(implicit networkSetting: NetworkSetting): Duration = {
    if (networkSetting.networkId == NetworkId.AlephiumMainNet) {
      RateLimiterWindowSizeMainnet
    } else {
      RateLimiterWindowSize
    }
  }
}
