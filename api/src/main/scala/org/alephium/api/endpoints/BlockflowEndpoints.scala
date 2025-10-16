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

package org.alephium.api.endpoints

import sttp.tapir._
import sttp.tapir.generic.auto._

import org.alephium.api.Endpoints._
import org.alephium.api.endpoints._
import org.alephium.api.model.{Address => _, _}
import org.alephium.protocol.model.{Balance => _, Transaction => _, _}

trait BlockflowEndpoints extends BaseEndpoints {

  private val blockflowEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("blockflow")
      .tag("Blockflow")

  val getBlocks: BaseEndpoint[TimeInterval, BlocksPerTimeStampRange] =
    blockflowEndpoint.get
      .in("blocks")
      .in(timeIntervalQuery)
      .out(jsonBody[BlocksPerTimeStampRange])
      .summary("List blocks on the given time interval")

  val getBlocksAndEvents: BaseEndpoint[TimeInterval, BlocksAndEventsPerTimeStampRange] =
    blockflowEndpoint.get
      .in("blocks-with-events")
      .in(timeIntervalQuery)
      .out(jsonBody[BlocksAndEventsPerTimeStampRange])
      .summary("List blocks with events on the given time interval")

  val getBlock: BaseEndpoint[BlockHash, BlockEntry] =
    blockflowEndpoint.get
      .in("blocks")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[BlockEntry])
      .summary("Get a block with hash")

  lazy val getRichBlocksAndEvents
      : BaseEndpoint[TimeInterval, RichBlocksAndEventsPerTimeStampRange] =
    blockflowEndpoint.get
      .in("rich-blocks")
      .in(timeIntervalQuery)
      .out(jsonBody[RichBlocksAndEventsPerTimeStampRange])
      .summary(
        "Given a time interval, list blocks containing events and transactions with enriched input information when node indexes are enabled."
      )

  lazy val getRichBlockAndEvents: BaseEndpoint[BlockHash, RichBlockAndEvents] =
    blockflowEndpoint.get
      .in("rich-blocks")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[RichBlockAndEvents])
      .summary(
        "Get a block containing events and transactions with enriched input information when node indexes are enabled."
      )

  lazy val getMainChainBlockByGhostUncle: BaseEndpoint[BlockHash, BlockEntry] =
    blockflowEndpoint.get
      .in("main-chain-block-by-ghost-uncle")
      .in(path[BlockHash]("ghost_uncle_hash"))
      .out(jsonBody[BlockEntry])
      .summary("Get a mainchain block by ghost uncle hash")

  val getBlockAndEvents: BaseEndpoint[BlockHash, BlockAndEvents] =
    blockflowEndpoint.get
      .in("blocks-with-events")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[BlockAndEvents])
      .summary("Get a block and events with hash")

  val isBlockInMainChain: BaseEndpoint[BlockHash, Boolean] =
    blockflowEndpoint.get
      .in("is-block-in-main-chain")
      .in(query[BlockHash]("blockHash"))
      .out(jsonBody[Boolean])
      .summary("Check if the block is in main chain")

  // have to be lazy to let `groupConfig` being initialized
  lazy val getHashesAtHeight: BaseEndpoint[(ChainIndex, Int), HashesAtHeight] =
    blockflowEndpoint.get
      .in("hashes")
      .in(chainIndexQuery)
      .in(query[Int]("height"))
      .out(jsonBody[HashesAtHeight])
      .summary("Get all block's hashes at given height for given groups")

  // have to be lazy to let `groupConfig` being initialized
  lazy val getChainInfo: BaseEndpoint[ChainIndex, ChainInfo] =
    blockflowEndpoint.get
      .in("chain-info")
      .in(chainIndexQuery)
      .out(jsonBody[ChainInfo])
      .summary("Get infos about the chain from the given groups")

  val getBlockHeaderEntry: BaseEndpoint[BlockHash, BlockHeaderEntry] =
    blockflowEndpoint.get
      .in("headers")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[BlockHeaderEntry])
      .summary("Get block header")

  val getRawBlock: BaseEndpoint[BlockHash, RawBlock] =
    blockflowEndpoint.get
      .in("raw-blocks")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[RawBlock])
      .summary("Get raw block in hex format")
}
