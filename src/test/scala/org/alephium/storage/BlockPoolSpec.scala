package org.alephium.storage

import org.alephium.AlephiumSpec
import org.alephium.protocol.Genesis
import org.alephium.protocol.model.ModelGen
import org.scalacheck.Gen

class BlockPoolSpec extends AlephiumSpec {

  behavior of "BlockPool"

  trait Fixture {
    val blockPool = new BlockPool()
    val genesis   = Genesis.block
  }

  it should "add block correctly" in new Fixture {
    blockPool.blockStore.size is 0
    forAll(ModelGen.blockGen) { block =>
      val blocksSize1 = blockPool.blockStore.size
      val txSize1     = blockPool.txStore.size
      blockPool.addBlock(block)
      val blocksSize2 = blockPool.blockStore.size
      val txSize2     = blockPool.txStore.size
      blocksSize1 + 1 is blocksSize2
      txSize1 + block.transactions.length is txSize2
    }
  }

  it should "add blocks correctly" in new Fixture {
    forAll(Gen.listOf(ModelGen.blockGen)) { blocks =>
      val blocksSize1 = blockPool.blockStore.size
      val txSize1     = blockPool.txStore.size
      blockPool.addBlocks(blocks)
      val blocksSize2 = blockPool.blockStore.size
      val txSize2     = blockPool.txStore.size
      blocksSize1 + blocks.size is blocksSize2
      txSize1 + blocks.map(_.transactions.length).sum is txSize2
    }
  }

  it should "work correctly for a chain of blocks" in {
    forAll(ModelGen.chainGen(5), minSuccessful(1)) { blocks =>
      val blockPool = new BlockPool()
      blockPool.addBlocks(blocks)
      val headBlock = blocks.head
      val lastBlock = blocks.last

      blockPool.getHeight(headBlock) is 1
      blockPool.getHeight(lastBlock) is blocks.size
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

  it should "work correctly with two chains of blocks" in {
    forAll(ModelGen.chainGen(3), minSuccessful(1)) { longChain =>
      forAll(ModelGen.chainGen(2), minSuccessful(1)) { shortChain =>
        val blockPool = new BlockPool()
        blockPool.addBlocks(longChain)
        blockPool.addBlocks(shortChain)

        blockPool.getHeight(longChain.head) is 1
        blockPool.getHeight(longChain.last) is longChain.size
        blockPool.getHeight(shortChain.head) is 1
        blockPool.getHeight(shortChain.last) is shortChain.size
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
