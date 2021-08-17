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

import org.alephium.flow.{AlephiumFlowSpec, FlowFixture}
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.protocol.BlockHash
import org.alephium.util.AVector

class BlockFetcherSpec extends AlephiumFlowSpec {
  it should "cleanup cache based on capacity and timestamp" in new FlowFixture {
    override val configValues = Map(
      ("alephium.broker.broker-num", 2),
      ("alephium.broker.groups", 2),
      ("alephium.network.sync-expiry-period", "2s")
    )

    val blockFetcher = BlockFetcher(blockFlow)
    blockFetcher.maxCapacity is (2 * 1 * 10)
    val hash0 = BlockHash.generate
    blockFetcher.fetch(hash0) is Some(BrokerHandler.DownloadBlocks(AVector(hash0)))
    blockFetcher.fetching.contains(hash0) is true
    val hashes = (0 until blockFetcher.maxCapacity).map { _ =>
      val hash = BlockHash.generate
      blockFetcher.fetch(hash) is Some(BrokerHandler.DownloadBlocks(AVector(hash)))
      blockFetcher.fetching.contains(hash) is true
      hash
    }
    blockFetcher.fetching.contains(hash0) is false
    blockFetcher.fetching.keys().toSet is hashes.toSet
    Thread.sleep(2500)
    val hash1 = BlockHash.generate
    blockFetcher.fetch(hash1) is Some(BrokerHandler.DownloadBlocks(AVector(hash1)))
    blockFetcher.fetching.keys().toSet is Set(hash1)
  }

  it should "fetch block" in {
    val blockFetcher = BlockFetcher(blockFlow)
    val blockHash    = BlockHash.generate
    blockFetcher.fetch(blockHash) is Some(BrokerHandler.DownloadBlocks(AVector(blockHash)))
    blockFetcher.fetch(blockHash) is Some(BrokerHandler.DownloadBlocks(AVector(blockHash)))
    blockFetcher.fetch(blockHash) is None
    blockFetcher.fetching.keys().toSet is Set(blockHash)
  }
}
