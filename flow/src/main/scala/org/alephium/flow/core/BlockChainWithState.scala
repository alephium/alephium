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
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.Block
import org.alephium.protocol.vm.WorldState

trait BlockChainWithState extends BlockChain {
  def worldStateStorage: WorldStateStorage

  def getPersistedWorldState(hash: Hash): IOResult[WorldState.Persisted] = {
    worldStateStorage.getPersistedWorldState(hash)
  }

  def getCachedWorldState(hash: Hash): IOResult[WorldState.Cached] = {
    worldStateStorage.getCachedWorldState(hash)
  }

  protected def addWorldState(hash: Hash, worldState: WorldState): IOResult[Unit] = {
    worldStateStorage.putTrie(hash, worldState)
  }

  def updateState(worldState: WorldState, block: Block): IOResult[WorldState]

  override def add(block: Block, weight: BigInt): IOResult[Unit] = {
    for {
      oldWorldState <- getCachedWorldState(block.parentHash)
      _             <- persistBlock(block)
      newWorldState <- updateState(oldWorldState, block)
      _             <- addWorldState(block.hash, newWorldState)
      _             <- add(block.header, weight)
    } yield ()
  }
}

object BlockChainWithState {
  def fromGenesisUnsafe(storages: Storages)(genesisBlock: Block,
                                            updateState: BlockFlow.TrieUpdater)(
      implicit brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting): BlockChainWithState = {
    val initialize = initializeGenesis(genesisBlock, storages.emptyWorldState)(_)
    createUnsafe(genesisBlock, storages, updateState, initialize)
  }

  def fromStorageUnsafe(storages: Storages)(genesisBlock: Block,
                                            updateState: BlockFlow.TrieUpdater)(
      implicit brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting): BlockChainWithState = {
    createUnsafe(genesisBlock, storages, updateState, initializeFromStorage)
  }

  def createUnsafe(
      rootBlock: Block,
      storages: Storages,
      _updateState: BlockFlow.TrieUpdater,
      initialize: BlockChainWithState => IOResult[Unit]
  )(implicit _brokerConfig: BrokerConfig,
    _consensusSetting: ConsensusSetting): BlockChainWithState = {
    val blockchain = new BlockChainWithState {
      override val brokerConfig      = _brokerConfig
      override val consensusConfig   = _consensusSetting
      override val blockStorage      = storages.blockStorage
      override val headerStorage     = storages.headerStorage
      override val blockStateStorage = storages.blockStateStorage
      override val worldStateStorage = storages.worldStateStorage
      override val heightIndexStorage =
        storages.nodeStateStorage.heightIndexStorage(rootBlock.chainIndex)
      override val chainStateStorage =
        storages.nodeStateStorage.chainStateStorage(rootBlock.chainIndex)
      override val genesisHash = rootBlock.hash

      override def updateState(worldState: WorldState, block: Block): IOResult[WorldState] =
        _updateState(worldState, block)
    }

    Utils.unsafe(initialize(blockchain))
    blockchain
  }

  def initializeGenesis(genesisBlock: Block, emptyWorldState: WorldState)(
      chain: BlockChainWithState): IOResult[Unit] = {
    for {
      _       <- chain.addGenesis(genesisBlock)
      newTrie <- chain.updateState(emptyWorldState, genesisBlock)
      _       <- chain.addWorldState(genesisBlock.hash, newTrie)
    } yield ()
  }

  def initializeFromStorage(chain: BlockChainWithState): IOResult[Unit] = {
    chain.loadFromStorage()
  }
}
