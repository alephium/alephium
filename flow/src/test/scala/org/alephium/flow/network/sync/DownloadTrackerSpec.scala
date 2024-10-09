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

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.model.{BlockHash, ChainIndex}
import org.alephium.util.{AVector, Duration, TimeStamp}

class DownloadTrackerSpec extends AlephiumFlowActorSpec {
  trait Fixture { F =>
    val blockflow = genesisBlockFlow()
    val hashes = AVector.from((1 to 5).map { k =>
      val block = transfer(blockflow, ChainIndex.unsafe(0, 0))
      addAndCheck(blockflow, block, k)
      block.hash
    })

    val downloadTrack = new DownloadTracker {
      override def blockflow: BlockFlow = F.blockflow
    }

    val randomHashes = AVector.fill(5)(BlockHash.generate)
  }

  it should "track downloading" in new Fixture {
    hashes.foreach(hash => blockFlow.contains(hash) isE true)

    downloadTrack.download(AVector(hashes)) is AVector.empty[BlockHash]
    downloadTrack.syncing.isEmpty is true

    downloadTrack.download(AVector(hashes ++ randomHashes)) is randomHashes
    downloadTrack.syncing.keys.toSet is randomHashes.toSet

    downloadTrack.download(AVector(hashes ++ randomHashes)) is AVector.empty[BlockHash]
    downloadTrack.syncing.keys.toSet is randomHashes.toSet

    hashes.foreach(downloadTrack.finalized)
    downloadTrack.syncing.keys.toSet is randomHashes.toSet

    (hashes ++ randomHashes).foreach(downloadTrack.finalized)
    downloadTrack.syncing.isEmpty is true
  }

  it should "cleanup expired downloading accordingly" in new Fixture {
    val currentTs = TimeStamp.now()
    val downloadingTs = AVector.tabulate(randomHashes.length)(k =>
      currentTs.minusUnsafe(Duration.ofMinutesUnsafe(k.toLong))
    )

    downloadingTs.indices.foreach { k =>
      downloadTrack.syncing.addOne(randomHashes(k) -> downloadingTs(k))
    }
    downloadTrack.syncing.size is randomHashes.length

    downloadTrack.cleanupSyncing(Duration.ofMinutesUnsafe(randomHashes.length.toLong)) is 0
    downloadTrack.syncing.size is randomHashes.length

    downloadTrack.cleanupSyncing(Duration.ofMinutesUnsafe(randomHashes.length.toLong - 1)) is 1
    downloadTrack.syncing.size is randomHashes.length - 1

    downloadTrack.cleanupSyncing(Duration.zero) is randomHashes.length - 1
    downloadTrack.syncing.size is 0
  }
}
