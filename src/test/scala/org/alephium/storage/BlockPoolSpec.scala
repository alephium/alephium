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
    val chainGen  = ModelGen.chainGen(5, Seq(genesis)).map(_.tail)
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
    forAll(ModelGen.chainGen(5, Seq(genesis)), minSuccessful(1)) { blocks =>
      val blockPool = ForksTree(genesis)
      blockPool.addBlocks(blocks.tail)
      val headBlock = blocks.head
      val lastBlock = blocks.last

      blockPool.getHeightFor(headBlock) is 1
      blockPool.getHeightFor(lastBlock) is blocks.size
      blockPool.getChain(headBlock) is Seq(headBlock)
      blockPool.getChain(lastBlock) is blocks
      blockPool.isHeader(headBlock) is false
      blockPool.isHeader(lastBlock) is true
      blockPool.getBestHeader is lastBlock
      blockPool.getBestChain is blocks
      blockPool.getHeight is blocks.size
      blockPool.getAllHeaders is Seq(lastBlock.hash)
    }
  }

  it should "work correctly with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, Seq(genesis)), minSuccessful(1)) { longChain =>
      forAll(ModelGen.chainGen(3, Seq(genesis)), minSuccessful(1)) { shortChain =>
        val blockPool = ForksTree(genesis)
        blockPool.addBlocks(longChain.tail)
        blockPool.addBlocks(shortChain.tail)

        blockPool.getHeightFor(longChain.head) is 1
        blockPool.getHeightFor(longChain.last) is longChain.size
        blockPool.getHeightFor(shortChain.head) is 1
        blockPool.getHeightFor(shortChain.last) is shortChain.size
        blockPool.getChain(longChain.head) is Seq(longChain.head)
        blockPool.getChain(longChain.last) is longChain
        blockPool.getChain(shortChain.head) is Seq(shortChain.head)
        blockPool.getChain(shortChain.last) is shortChain
        blockPool.isHeader(longChain.head) is false
        blockPool.isHeader(longChain.last) is true
        blockPool.isHeader(shortChain.head) is false
        blockPool.isHeader(shortChain.last) is true
        blockPool.getBestHeader is longChain.last
        blockPool.getBestChain is longChain
        blockPool.getHeight is longChain.size
        blockPool.getAllHeaders.toSet is Set(longChain.last.hash, shortChain.last.hash)
      }
    }
  }

  // TODO: add tests for balance
}
