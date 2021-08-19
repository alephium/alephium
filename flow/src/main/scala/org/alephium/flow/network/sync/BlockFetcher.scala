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
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.util.{AVector, Cache, TimeStamp}

object BlockFetcher {
  val MaxDownloadTimes: Int = 2

  final case class State(timestamp: TimeStamp, downloadTimes: Int)
  def apply(
      blockflow: BlockFlow
  )(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): BlockFetcher =
    new BlockFetcher(blockflow)
}

final class BlockFetcher(val blockflow: BlockFlow)(implicit
    brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting
) {
  import BlockFetcher._
  val maxCapacity: Int = brokerConfig.groupNumPerBroker * brokerConfig.groups * 10
  val fetching: Cache[BlockHash, State] =
    Cache.fifo(maxCapacity, _.timestamp, networkSetting.syncExpiryPeriod)

  def fetch(hash: BlockHash): Option[BrokerHandler.Command] = {
    if (!blockflow.containsUnsafe(hash)) {
      fetching.get(hash) match {
        case Some(state) if state.downloadTimes < MaxDownloadTimes =>
          fetching.put(hash, State(TimeStamp.now(), state.downloadTimes + 1))
          Some(BrokerHandler.DownloadBlocks(AVector(hash)))
        case None =>
          fetching.put(hash, State(TimeStamp.now(), 1))
          Some(BrokerHandler.DownloadBlocks(AVector(hash)))
        case _ => None
      }
    } else {
      None
    }
  }
}
