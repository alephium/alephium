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

package org.alephium.flow.core

import org.alephium.flow.io.Storages
import org.alephium.flow.setting.AlephiumConfig
import org.alephium.io.IOResult
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.WorldState
import org.alephium.util.{AVector, TimeStamp}

trait EmptyBlockFlow extends BlockFlow {
  def storages: Storages
  implicit def config: AlephiumConfig

  implicit lazy val brokerConfig: BrokerConfig = config.broker
  implicit lazy val consensusConfig            = config.consensus
  implicit lazy val networkConfig              = config.network
  implicit lazy val mempoolSetting             = config.mempool

  lazy val genesisBlocks: AVector[AVector[Block]] = config.genesisBlocks

  lazy val blockchainWithStateBuilder: (Block, BlockFlow.WorldStateUpdater) => BlockChainWithState =
    BlockChainWithState.fromGenesisUnsafe(storages)
  lazy val blockchainBuilder: Block => BlockChain =
    BlockChain.fromGenesisUnsafe(storages)
  lazy val blockheaderChainBuilder: BlockHeader => BlockHeaderChain =
    BlockHeaderChain.fromGenesisUnsafe(storages)

  def getAllTips: AVector[BlockHash]                                                           = ???
  def getBestTipUnsafe(): BlockHash                                                            = ???
  def calBestDepsUnsafe(group: GroupIndex): BlockDeps                                          = ???
  def updateBestDeps(): IOResult[AVector[(TransactionTemplate, TimeStamp)]]                    = ???
  def updateBestDepsUnsafe(): AVector[(TransactionTemplate, TimeStamp)]                        = ???
  def getBestIntraGroupTip(): BlockHash                                                        = ???
  def add(block: Block, worldStateOpt: Option[WorldState.Cached]): IOResult[Unit]              = ???
  def add(header: BlockHeader): IOResult[Unit]                                                 = ???
  def addAndUpdateView(block: Block, worldStateOpt: Option[WorldState.Cached]): IOResult[Unit] = ???
  def addAndUpdateView(header: BlockHeader): IOResult[Unit]                                    = ???
}
