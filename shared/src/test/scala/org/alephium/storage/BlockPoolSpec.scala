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
      blockPool.add(block)
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
      blockPool.addBlocks(blocks)
      val blocksSize2 = blockPool.numBlocks
      val txSize2     = blockPool.numTransactions
      blocksSize1 + blocks.size is blocksSize2
      txSize1 + blocks.map(_.transactions.length).sum is txSize2
    }
  }

  it should "work correctly for a chain of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis), minSuccessful(1)) { blocks =>
      val blockPool = ForksTree(genesis)
      blockPool.addBlocks(blocks)
      val headBlock = genesis
      val lastBlock = blocks.last
      val poolSize  = blocks.size + 1

      blockPool.getHeightFor(headBlock) is 1
      blockPool.getHeightFor(lastBlock) is poolSize
      blockPool.getChain(headBlock) is Seq(headBlock)
      blockPool.getChain(lastBlock) is genesis +: blocks
      blockPool.isHeader(headBlock) is false
      blockPool.isHeader(lastBlock) is true
      blockPool.getBestHeader is lastBlock
      blockPool.getBestChain is genesis +: blocks
      blockPool.getHeight is poolSize
      blockPool.getAllHeaders is Seq(lastBlock.hash)
    }
  }

  it should "work correctly with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(3, genesis), minSuccessful(1)) { longChain =>
      forAll(ModelGen.chainGen(2, genesis), minSuccessful(1)) { shortChain =>
        val blockPool = ForksTree(genesis)
        blockPool.addBlocks(longChain)
        blockPool.addBlocks(shortChain)

        blockPool.getHeightFor(longChain.head) is 2
        blockPool.getHeightFor(longChain.last) is longChain.size + 1
        blockPool.getHeightFor(shortChain.head) is 2
        blockPool.getHeightFor(shortChain.last) is shortChain.size + 1
        blockPool.getChain(longChain.head) is Seq(genesis, longChain.head)
        blockPool.getChain(longChain.last) is genesis +: longChain
        blockPool.getChain(shortChain.head) is Seq(genesis, shortChain.head)
        blockPool.getChain(shortChain.last) is genesis +: shortChain
        blockPool.isHeader(longChain.head) is false
        blockPool.isHeader(longChain.last) is true
        blockPool.isHeader(shortChain.head) is false
        blockPool.isHeader(shortChain.last) is true
        blockPool.getBestHeader is longChain.last
        blockPool.getBestChain is genesis +: longChain
        blockPool.getHeight is longChain.size + 1
        blockPool.getAllHeaders.toSet is Set(longChain.last.hash, shortChain.last.hash)
      }
    }
  }

  // TODO: add tests for balance
}
