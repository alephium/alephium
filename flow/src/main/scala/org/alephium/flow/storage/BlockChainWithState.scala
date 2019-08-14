package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.IOResult
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.model.Block
import org.alephium.util.ConcurrentHashMap

trait BlockChainWithState extends BlockChain {
  private val tries = ConcurrentHashMap.empty[Keccak256, MerklePatriciaTrie]

  def getTrie(hash: Keccak256): MerklePatriciaTrie = {
    assert(tries.contains(hash))
    tries(hash)
  }

  def addTrie(hash: Keccak256, trie: MerklePatriciaTrie): Unit = {
    tries.add(hash, trie)
  }

  def updateState(trie: MerklePatriciaTrie, block: Block): IOResult[MerklePatriciaTrie]

  override def add(block: Block, parentHash: Keccak256, weight: Int): IOResult[Unit] = {
    val trie = getTrie(block.parentHash)
    for {
      _       <- super.add(block, parentHash, weight)
      newTrie <- updateState(trie, block)
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

      this.persistBlockUnsafe(rootBlock)
      this.addHeaderUnsafe(rootBlock.header)
      this.addNode(rootNode)
      this.addTrie(rootNode.blockHash, initialTrie)
    }
  }
}
