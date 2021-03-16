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
import org.alephium.util.{AVector, BaseActor}

trait DownloadTracker extends BaseActor {
  def blockflow: BlockFlow

  val downloading: mutable.HashSet[BlockHash] = mutable.HashSet.empty

  def needToDownload(hash: BlockHash): Boolean =
    !(blockflow.containsUnsafe(hash) || downloading.contains(hash))

  def download(hashes: AVector[AVector[BlockHash]]): Unit = {
    val toDownload = hashes.flatMap(_.filter(needToDownload))
    toDownload.foreach(downloading.addOne)
    log.debug(s"Downloading ${toDownload.length} blocks")
    sender() ! BrokerHandler.DownloadBlocks(toDownload)
  }

  def finalized(hash: BlockHash): Unit = {
    downloading.remove(hash)
    ()
  }
}
