package org.alephium.flow.core

import org.alephium.flow.Utils
import org.alephium.flow.io._
import org.alephium.flow.platform.PlatformConfig
import org.alephium.flow.trie.WorldState
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.io.IOResult
import org.alephium.protocol.model.{Block, ChainIndex}

trait BlockChainWithState extends BlockChain {
  def trieHashStorage: TrieHashStorage

  def getTrie(hash: Hash): IOResult[WorldState] = {
    trieHashStorage.getTrie(hash)
  }

  protected def addTrie(hash: Hash, worldState: WorldState): IOResult[Unit] = {
    trieHashStorage.putTrie(hash, worldState)
  }

  def updateState(worldState: WorldState, block: Block): IOResult[WorldState]

  override def add(block: Block, weight: BigInt): IOResult[Unit] = {
    for {
      oldTrie <- getTrie(block.parentHash)
      newTrie <- updateState(oldTrie, block)
      _       <- addTrie(block.hash, newTrie)
      _       <- super.add(block, weight)
    } yield ()
  }
}

object BlockChainWithState {
  def fromGenesisUnsafe(storages: Storages)(
      chainIndex: ChainIndex,
      updateState: BlockFlow.TrieUpdater)(implicit config: PlatformConfig): BlockChainWithState = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    val initialize   = initializeGenesis(genesisBlock, storages.emptyWorldState)(_)
    createUnsafe(chainIndex, genesisBlock, storages, updateState, initialize)
  }

  def fromStorageUnsafe(storages: Storages)(
      chainIndex: ChainIndex,
      updateState: BlockFlow.TrieUpdater)(implicit config: PlatformConfig): BlockChainWithState = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    createUnsafe(chainIndex, genesisBlock, storages, updateState, initializeFromStorage)
  }

  def createUnsafe(
      chainIndex: ChainIndex,
      rootBlock: Block,
      storages: Storages,
      _updateState: BlockFlow.TrieUpdater,
      initialize: BlockChainWithState => IOResult[Unit]
  )(implicit _config: PlatformConfig): BlockChainWithState = {
    val blockchain = new BlockChainWithState {
      override implicit val config    = _config
      override val blockStorage       = storages.blockStorage
      override val headerStorage      = storages.headerStorage
      override val blockStateStorage  = storages.blockStateStorage
      override val trieHashStorage    = storages.trieHashStorage
      override val heightIndexStorage = storages.nodeStateStorage.heightIndexStorage(chainIndex)
      override val chainStateStorage  = storages.nodeStateStorage.chainStateStorage(chainIndex)
      override val genesisHash        = rootBlock.hash

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
      _       <- chain.addTrie(genesisBlock.hash, newTrie)
    } yield ()
  }

  def initializeFromStorage(chain: BlockChainWithState): IOResult[Unit] = {
    chain.loadFromStorage()
  }
}
