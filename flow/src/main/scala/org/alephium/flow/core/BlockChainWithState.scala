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

import org.alephium.flow.Utils
import org.alephium.flow.io._
import org.alephium.flow.setting.ConsensusSettings
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, NetworkConfig}
import org.alephium.protocol.model.{Block, BlockHash, Weight}
import org.alephium.protocol.vm.WorldState

trait BlockChainWithState extends BlockChain {
  def worldStateStorage: WorldStateStorage

  def getPersistedWorldState(hash: BlockHash): IOResult[WorldState.Persisted] = {
    worldStateStorage.getPersistedWorldState(hash)
  }

  def getWorldStateHash(hash: BlockHash): IOResult[Hash] = {
    worldStateStorage.getWorldStateHash(hash)
  }

  def getCachedWorldState(hash: BlockHash): IOResult[WorldState.Cached] = {
    worldStateStorage.getCachedWorldState(hash)
  }

  protected def addWorldState(hash: BlockHash, worldState: WorldState.Persisted): IOResult[Unit] = {
    worldStateStorage.putTrie(hash, worldState)
  }

  def updateState(worldState: WorldState.Cached, block: Block): IOResult[Unit]

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  override def add(
      block: Block,
      weight: Weight,
      worldStateOpt: Option[WorldState.Cached]
  ): IOResult[Unit] = {
    assume(worldStateOpt.nonEmpty)
    val cachedWorldState = worldStateOpt.get
    for {
      _             <- persistBlock(block)
      _             <- persistTxs(block)
      _             <- updateState(cachedWorldState, block)
      newWorldState <- cachedWorldState.persist()
      _             <- addWorldState(block.hash, newWorldState)
      _             <- add(block.header, weight)
    } yield ()
  }

  override def checkCompletenessUnsafe(hash: BlockHash): Boolean = {
    checkCompletenessHelper(hash, worldStateStorage.existsUnsafe, super.checkCompletenessUnsafe)
  }
}

object BlockChainWithState {
  def fromGenesisUnsafe(
      storages: Storages
  )(genesisBlock: Block, updateState: BlockFlow.WorldStateUpdater)(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusSettings: ConsensusSettings
  ): BlockChainWithState = {
    val initialize = initializeGenesis(genesisBlock, storages.emptyWorldState)(_)
    createUnsafe(genesisBlock, storages, updateState, initialize)
  }

  def fromStorageUnsafe(
      storages: Storages
  )(genesisBlock: Block, updateState: BlockFlow.WorldStateUpdater)(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusSettings: ConsensusSettings
  ): BlockChainWithState = {
    createUnsafe(genesisBlock, storages, updateState, initializeFromStorage)
  }

  def createUnsafe(
      rootBlock: Block,
      storages: Storages,
      _updateState: BlockFlow.WorldStateUpdater,
      initialize: BlockChainWithState => IOResult[Unit]
  )(implicit
      _brokerConfig: BrokerConfig,
      _networkConfig: NetworkConfig,
      _consensusSettings: ConsensusSettings
  ): BlockChainWithState = {
    val blockchain = new BlockChainWithState {
      override val brokerConfig      = _brokerConfig
      override val networkConfig     = _networkConfig
      override val consensusConfigs  = _consensusSettings
      override val blockStorage      = storages.blockStorage
      override val txStorage         = storages.txStorage
      override val headerStorage     = storages.headerStorage
      override val blockStateStorage = storages.blockStateStorage
      override val worldStateStorage = storages.worldStateStorage
      override val heightIndexStorage =
        storages.nodeStateStorage.heightIndexStorage(rootBlock.chainIndex)
      override val chainStateStorage =
        storages.nodeStateStorage.chainStateStorage(rootBlock.chainIndex)
      override val genesisHash = rootBlock.hash

      override def updateState(worldState: WorldState.Cached, block: Block): IOResult[Unit] =
        _updateState(worldState, block)
    }

    Utils.unsafe(initialize(blockchain))
    blockchain
  }

  def initializeGenesis(genesisBlock: Block, emptyWorldState: WorldState.Persisted)(
      chain: BlockChainWithState
  ): IOResult[Unit] = {
    val initialWorldState = emptyWorldState.cached()
    for {
      _             <- chain.addGenesis(genesisBlock)
      _             <- chain.updateState(initialWorldState, genesisBlock)
      newWorldState <- initialWorldState.persist()
      _             <- chain.addWorldState(genesisBlock.hash, newWorldState)
    } yield ()
  }

  def initializeFromStorage(chain: BlockChainWithState): IOResult[Unit] = {
    chain.loadFromStorage()
  }
}
