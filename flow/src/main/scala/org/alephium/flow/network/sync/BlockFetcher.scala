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

package org.alephium.flow.network.sync

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.BlockHash
import org.alephium.util.{AVector, BaseActor, TimeStamp}

object BlockFetcher {
  val MaxDownloadTimes: Int = 2
}

trait BlockFetcher extends BaseActor {
  import BlockFetcher.MaxDownloadTimes
  def networkSetting: NetworkSetting
  def brokerConfig: BrokerConfig
  def blockflow: BlockFlow

  val maxCapacity: Int = brokerConfig.groupNumPerBroker * brokerConfig.groups * 10
  val fetching =
    new FetchState[BlockHash](maxCapacity, networkSetting.syncExpiryPeriod, MaxDownloadTimes)

  def handleBlockAnnouncement(hash: BlockHash): Unit = {
    if (!blockflow.containsUnsafe(hash)) {
      val timestamp = TimeStamp.now()
      if (fetching.needToFetch(hash, timestamp)) {
        sender() ! BrokerHandler.DownloadBlocks(AVector(hash))
      }
    }
  }
}
