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
import org.alephium.protocol.BlockHash
import org.alephium.util.{ActorRefT, AVector, BaseActor, Duration, TimeStamp, UnsecureRandom}

trait DownloadTracker extends BaseActor {
  def blockflow: BlockFlow

  val downloading: mutable.HashMap[BlockHash, TimeStamp] = mutable.HashMap.empty
  val announcements: mutable.HashMap[BlockHash, mutable.Set[ActorRefT[BrokerHandler.Command]]] =
    mutable.HashMap.empty

  def addAnnouncement(hash: BlockHash, broker: ActorRefT[BrokerHandler.Command]): Unit = {
    announcements.get(hash) match {
      case None          => announcements += hash -> mutable.Set(broker)
      case Some(brokers) => brokers += broker
    }
  }

  def removeAnnouncement(broker: ActorRefT[BrokerHandler.Command]): Unit = {
    announcements.foreach { case (hash, brokers) =>
      brokers -= broker
      if (brokers.isEmpty) {
        announcements -= hash
      }
    }
  }

  def handleAnnouncement(hash: BlockHash, broker: ActorRefT[BrokerHandler.Command]): Unit = {
    if (!blockflow.containsUnsafe(hash)) {
      addAnnouncement(hash, broker)
      if (!downloading.contains(hash)) {
        downloading += hash -> TimeStamp.now()
        broker ! BrokerHandler.DownloadBlocks(AVector(hash))
      }
    }
  }

  def needToDownload(hash: BlockHash): Boolean =
    !(blockflow.containsUnsafe(hash) || downloading.contains(hash))

  def download(hashes: AVector[AVector[BlockHash]]): Unit = {
    val currentTs  = TimeStamp.now()
    val toDownload = hashes.flatMap(_.filter(needToDownload))
    toDownload.foreach(hash => downloading.addOne(hash -> currentTs))
    sender() ! BrokerHandler.DownloadBlocks(toDownload)
  }

  def finalized(hash: BlockHash): Unit = {
    downloading -= hash
    announcements -= hash
  }

  def cleanupDownloading(aliveDuration: Duration): Unit = {
    val threshold   = TimeStamp.now().minusUnsafe(aliveDuration)
    val needToRetry = mutable.ArrayBuffer.empty[BlockHash]
    val oldSize     = downloading.size
    downloading.filterInPlace { case (hash, downloadTs) =>
      val isExpired = downloadTs <= threshold
      if (isExpired && announcements.contains(hash)) {
        needToRetry += hash
      }
      !isExpired
    }
    val newSize   = downloading.size
    val sizeDelta = oldSize - newSize
    if (sizeDelta > 0) {
      log.debug(s"Clean up #sizeDelta hashes from downloading pool")
    }
    retryDownloadAnnouncements(needToRetry)
  }

  def retryDownloadAnnouncements(hashes: Iterable[BlockHash]): Unit = {
    val currentTs = TimeStamp.now()
    hashes.foreach { hash =>
      val brokers        = announcements(hash)
      val index          = UnsecureRandom.source.nextInt(brokers.size)
      val selectedBroker = brokers.toIndexedSeq.apply(index)
      downloading += hash -> currentTs
      selectedBroker ! BrokerHandler.DownloadBlocks(AVector(hash))
    }
  }
}
