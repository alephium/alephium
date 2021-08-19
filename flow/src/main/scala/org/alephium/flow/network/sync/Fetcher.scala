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
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{AVector, BaseActor, Cache, TimeStamp}

object Fetcher {
  val MaxDownloadTimes: Int = 2

  final case class State(timestamp: TimeStamp, downloadTimes: Int)
}

trait Fetcher extends BaseActor {
  import Fetcher._
  def networkSetting: NetworkSetting
  def brokerConfig: BrokerConfig
  def blockflow: BlockFlow
  def isNodeSynced: Boolean

  val maxCapacity: Int = brokerConfig.groupNumPerBroker * brokerConfig.groups * 10
  val fetchingBlocks: Cache[BlockHash, State] =
    Cache.fifo(maxCapacity, _.timestamp, networkSetting.syncExpiryPeriod)
  val fetchingTxs: Cache[Hash, State] =
    Cache.fifo(maxCapacity, _.timestamp, networkSetting.syncExpiryPeriod)

  def needToFetch[T](fetching: Cache[T, State], inventory: T, timestamp: TimeStamp): Boolean = {
    fetching.get(inventory) match {
      case Some(state) if state.downloadTimes < MaxDownloadTimes =>
        fetching.put(inventory, State(timestamp, state.downloadTimes + 1))
        true
      case None =>
        fetching.put(inventory, State(timestamp, 1))
        true
      case _ => false
    }
  }

  def handleBlockAnnouncement(hash: BlockHash): Unit = {
    if (!blockflow.containsUnsafe(hash)) {
      val timestamp = TimeStamp.now()
      if (needToFetch(fetchingBlocks, hash, timestamp)) {
        sender() ! BrokerHandler.DownloadBlocks(AVector(hash))
      }
    }
  }

  def handleTxsAnnouncement(hashes: AVector[(ChainIndex, AVector[Hash])]): Unit = {
    if (isNodeSynced) {
      val timestamp = TimeStamp.now()
      val invs = hashes.fold(AVector.empty[(ChainIndex, AVector[Hash])]) {
        case (acc, (chainIndex, txHashes)) =>
          val mempool = blockflow.getMemPool(chainIndex)
          val selected = txHashes.filter { hash =>
            !mempool.contains(chainIndex, hash) &&
            needToFetch(fetchingTxs, hash, timestamp)
          }
          if (selected.nonEmpty) {
            acc :+ ((chainIndex, selected))
          } else {
            acc
          }
      }
      if (invs.nonEmpty) {
        sender() ! BrokerHandler.DownloadTxs(invs)
      }
    }
  }
}
