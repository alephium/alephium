package org.alephium.flow.core

import org.alephium.flow.io._
import org.alephium.flow.platform.PlatformConfig
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.ConcurrentHashMap

trait BlockChainWithState extends BlockChain {
  private val tries = ConcurrentHashMap.empty[Hash, MerklePatriciaTrie]

  def getTrie(hash: Hash): MerklePatriciaTrie = {
    assert(tries.contains(hash))
    tries(hash)
  }

  protected def addTrie(hash: Hash, trie: MerklePatriciaTrie): Unit = {
    tries.add(hash, trie)
  }

  def updateState(trie: MerklePatriciaTrie, block: Block): IOResult[MerklePatriciaTrie]

  override def add(block: Block, weight: BigInt): IOResult[Unit] = {
    val trie = getTrie(block.parentHash)
    for {
      newTrie <- updateState(trie, block)
      _       <- super.add(block, weight)
    } yield {
      addTrie(block.hash, newTrie)
    }
  }
}

object BlockChainWithState {
  def fromGenesisUnsafe(chainIndex: ChainIndex, updateState: BlockFlow.TrieUpdater)(
      implicit config: PlatformConfig): BlockChainWithState = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    createUnsafe(chainIndex, genesisBlock, config.storages, updateState)
  }

  def createUnsafe(
      chainIndex: ChainIndex,
      rootBlock: Block,
      storages: Storages,
      _updateState: BlockFlow.TrieUpdater
  )(implicit _config: PlatformConfig): BlockChainWithState = {
    new BlockChainWithState {
      override implicit val config: PlatformConfig      = _config
      override val blockStorage: BlockStorage           = storages.blockStorage
      override val headerStorage: BlockHeaderStorage    = storages.headerStorage
      override val blockStateStorage: BlockStateStorage = storages.blockStateStorage
      override val heightIndexStorage                   = storages.nodeStateStorage.heightIndexStorage(chainIndex)
      override val tipsDB                               = storages.nodeStateStorage.hashTreeTipsDB(chainIndex)
      override val genesisHash: Hash                    = rootBlock.hash

      override def updateState(trie: MerklePatriciaTrie,
                               block: Block): IOResult[MerklePatriciaTrie] =
        _updateState(trie, block)

      val updateRes = for {
        _       <- this.addGenesis(rootBlock)
        newTrie <- _updateState(storages.trie, rootBlock)
      } yield {
        this.addTrie(rootBlock.hash, newTrie)
      }
      require(updateRes.isRight)
    }
  }
}
