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
    blockPool.blockStore.size shouldBe 0
    forAll(ModelGen.blockGen) { block =>
      val blocksSize1 = blockPool.blockStore.size
      val txSize1     = blockPool.txStore.size
      blockPool.addBlock(block)
      val blocksSize2 = blockPool.blockStore.size
      val txSize2     = blockPool.txStore.size
      blocksSize1 + 1 shouldBe blocksSize2
      txSize1 + block.transactions.length shouldBe txSize2
    }
  }

  it should "add blocks correctly" in new Fixture {
    forAll(Gen.listOf(ModelGen.blockGen)) { blocks =>
      val blocksSize1 = blockPool.blockStore.size
      val txSize1     = blockPool.txStore.size
      blockPool.addBlocks(blocks)
      val blocksSize2 = blockPool.blockStore.size
      val txSize2     = blockPool.txStore.size
      blocksSize1 + blocks.size shouldBe blocksSize2
      txSize1 + blocks.map(_.transactions.length).sum shouldBe txSize2
    }
  }

  it should "work correctly for a chain of blocks" in {
    forAll(ModelGen.chainGen(5), minSuccessful(1)) { blocks =>
      val blockPool = new BlockPool()
      blockPool.addBlocks(blocks)
      val headBlock = blocks.head
      val lastBlock = blocks.last

      blockPool.getHeight(headBlock) shouldBe 1
      blockPool.getHeight(lastBlock) shouldBe blocks.size
      blockPool.getChain(headBlock) shouldBe Seq(headBlock)
      blockPool.getChain(lastBlock) shouldBe blocks
      blockPool.isHeader(headBlock) shouldBe false
      blockPool.isHeader(lastBlock) shouldBe true
      blockPool.getBestHeader shouldBe lastBlock
      blockPool.getBestChain shouldBe blocks
      blockPool.getHeight shouldEq blocks.size
      blockPool.getAllHeaders shouldEq Seq(lastBlock.hash)
    }
  }

  it should "work correctly with two chains of blocks" in {
    forAll(ModelGen.chainGen(3), minSuccessful(1)) { longChain =>
      forAll(ModelGen.chainGen(2), minSuccessful(1)) { shortChain =>
        val blockPool = new BlockPool()
        blockPool.addBlocks(longChain)
        blockPool.addBlocks(shortChain)

        blockPool.getHeight(longChain.head) shouldBe 1
        blockPool.getHeight(longChain.last) shouldBe longChain.size
        blockPool.getHeight(shortChain.head) shouldBe 1
        blockPool.getHeight(shortChain.last) shouldBe shortChain.size
        blockPool.getChain(longChain.head) shouldBe Seq(longChain.head)
        blockPool.getChain(longChain.last) shouldBe longChain
        blockPool.getChain(shortChain.head) shouldBe Seq(shortChain.head)
        blockPool.getChain(shortChain.last) shouldBe shortChain
        blockPool.isHeader(longChain.head) shouldBe false
        blockPool.isHeader(longChain.last) shouldBe true
        blockPool.isHeader(shortChain.head) shouldBe false
        blockPool.isHeader(shortChain.last) shouldBe true
        blockPool.getBestHeader shouldBe longChain.last
        blockPool.getBestChain shouldBe longChain
        blockPool.getHeight shouldEq longChain.size
        blockPool.getAllHeaders.toSet shouldBe Set(longChain.last.hash, shortChain.last.hash)
      }
    }
  }

  // TODO: add tests for balance
}
