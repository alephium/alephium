package org.alephium.flow.core

import org.alephium.flow.io.IOResult
import org.alephium.flow.platform.PlatformConfig
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.Block
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

  override def add(block: Block, parentHash: Hash, weight: Int): IOResult[Unit] = {
    val trie = getTrie(block.parentHash)
    for {
      newTrie <- updateState(trie, block)
      _       <- super.add(block, parentHash, weight)
    } yield {
      addTrie(block.hash, newTrie)
    }
  }
}

object BlockChainWithState {
  def fromGenesisUnsafe(genesis: Block,
                        updateState: (MerklePatriciaTrie, Block) => IOResult[MerklePatriciaTrie])(
      implicit config: PlatformConfig): BlockChainWithState =
    createUnsafe(genesis, 0, 0, config.emptyTrie, updateState)

  private def createUnsafe(
      rootBlock: Block,
      initialHeight: Int,
      initialWeight: Int,
      initialTrie: MerklePatriciaTrie,
      _updateState: (MerklePatriciaTrie, Block) => IOResult[MerklePatriciaTrie])(
      implicit _config: PlatformConfig): BlockChainWithState = {
    val timestamp = rootBlock.header.timestamp
    val rootNode  = BlockHashChain.Root(rootBlock.hash, initialHeight, initialWeight, timestamp)

    new BlockChainWithState {
      override val disk                                = _config.disk
      override val headerDB                            = _config.headerDB
      override implicit val config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      override def updateState(trie: MerklePatriciaTrie,
                               block: Block): IOResult[MerklePatriciaTrie] =
        _updateState(trie, block)

      val updateRes = for {
        _       <- this.persistBlock(rootBlock)
        _       <- this.addHeader(rootBlock.header)
        newTrie <- _updateState(initialTrie, rootBlock)
      } yield {
        this.addTrie(rootNode.blockHash, newTrie)
        this.addNode(rootNode)
      }
      require(updateRes.isRight)
    }
  }
}
