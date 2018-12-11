package org.alephium.storage

import org.alephium.AlephiumSpec
import org.alephium.protocol.Genesis
import org.alephium.protocol.model.ModelGen

class BlockPoolSpec extends AlephiumSpec {

  behavior of "BlockPool"

  trait Fixture {
    val genesis   = Genesis.block
    val blockPool = ForksTree(genesis)
    val blockGen  = ModelGen.blockGenWith(Seq(genesis.hash))
    val chainGen  = ModelGen.chainGen(4, genesis)
  }

  it should "add block correctly" in new Fixture {
    blockPool.numBlocks is 1
    forAll(blockGen) { block =>
      val blocksSize1 = blockPool.numBlocks
      val txSize1     = blockPool.numTransactions
      blockPool.add(block, 0)
      val blocksSize2 = blockPool.numBlocks
      val txSize2     = blockPool.numTransactions
      blocksSize1 + 1 is blocksSize2
      txSize1 + block.transactions.length is txSize2
    }
  }

  it should "add blocks correctly" in new Fixture {
    forAll(chainGen) { blocks =>
      val blocksSize1 = blockPool.numBlocks
      val txSize1     = blockPool.numTransactions
      blocks.foreach(block => blockPool.add(block, 0))
      val blocksSize2 = blockPool.numBlocks
      val txSize2     = blockPool.numTransactions
      blocksSize1 + blocks.size is blocksSize2
      txSize1 + blocks.map(_.transactions.length).sum is txSize2
    }
  }

  it should "work correctly for a chain of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis), minSuccessful(1)) { blocks =>
      val blockPool = ForksTree(genesis, 0, 0)
      blocks.foreach(block => blockPool.add(block, 0))
      val headBlock = genesis
      val lastBlock = blocks.last

      blockPool.getHeight(headBlock) is 0
      blockPool.getHeight(lastBlock) is blocks.size
      blockPool.getChainSlice(headBlock) is Seq(headBlock)
      blockPool.getChainSlice(lastBlock) is genesis +: blocks
      blockPool.isHeader(headBlock) is false
      blockPool.isHeader(lastBlock) is true
      blockPool.getBestHeader is lastBlock
      blockPool.getBestChain is genesis +: blocks
      blockPool.maxHeight is blocks.size
      blockPool.getAllHeaders is Seq(lastBlock.hash)
    }
  }

  it should "work correctly with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(3, genesis), minSuccessful(1)) { longChain =>
      forAll(ModelGen.chainGen(2, genesis), minSuccessful(1)) { shortChain =>
        val blockPool = ForksTree(genesis, 0, 0)
        longChain.foreach(block  => blockPool.add(block, 0))
        shortChain.foreach(block => blockPool.add(block, 0))

        blockPool.getHeight(longChain.head) is 1
        blockPool.getHeight(longChain.last) is longChain.size
        blockPool.getHeight(shortChain.head) is 1
        blockPool.getHeight(shortChain.last) is shortChain.size
        blockPool.getChainSlice(longChain.head) is Seq(genesis, longChain.head)
        blockPool.getChainSlice(longChain.last) is genesis +: longChain
        blockPool.getChainSlice(shortChain.head) is Seq(genesis, shortChain.head)
        blockPool.getChainSlice(shortChain.last) is genesis +: shortChain
        blockPool.isHeader(longChain.head) is false
        blockPool.isHeader(longChain.last) is true
        blockPool.isHeader(shortChain.head) is false
        blockPool.isHeader(shortChain.last) is true
        blockPool.getBestHeader is longChain.last
        blockPool.getBestChain is genesis +: longChain
        blockPool.maxHeight is longChain.size
        blockPool.getAllHeaders.toSet is Set(longChain.last.hash, shortChain.last.hash)
      }
    }
  }
}
