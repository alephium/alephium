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

import scala.collection.mutable

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.util.{AVector, BaseActor, Duration, TimeStamp}

trait DownloadTracker extends BaseActor {
  def blockflow: BlockFlow
  implicit def brokerConfig: BrokerConfig
  implicit def networkSetting: NetworkSetting

  val syncing: mutable.HashMap[BlockHash, TimeStamp] = mutable.HashMap.empty
  val blockFetcher: BlockFetcher                     = BlockFetcher(blockflow)

  def handleAnnouncement(hash: BlockHash): Unit = {
    blockFetcher.fetch(hash).foreach(sender() ! _)
  }

  def needToDownload(hash: BlockHash): Boolean =
    !(blockflow.containsUnsafe(hash) || syncing.contains(hash))

  def download(hashes: AVector[AVector[BlockHash]]): Unit = {
    val currentTs  = TimeStamp.now()
    val toDownload = hashes.flatMap(_.filter(needToDownload))
    toDownload.foreach(hash => syncing.addOne(hash -> currentTs))
    sender() ! BrokerHandler.DownloadBlocks(toDownload)
  }

  def finalized(hash: BlockHash): Unit = {
    syncing -= hash
  }

  def cleanupSyncing(aliveDuration: Duration): Unit = {
    val threshold = TimeStamp.now().minusUnsafe(aliveDuration)
    val oldSize   = syncing.size
    syncing.filterInPlace { case (_, timestamp) =>
      timestamp > threshold
    }
    val newSize   = syncing.size
    val sizeDelta = oldSize - newSize
    if (sizeDelta > 0) {
      log.debug(s"Clean up #$sizeDelta hashes from syncing pool")
    }
  }
}
