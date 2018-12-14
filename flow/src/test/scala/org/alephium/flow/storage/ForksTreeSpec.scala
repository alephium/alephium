package org.alephium.flow.storage

import org.alephium.flow.constant.{Consensus, Genesis}
import org.alephium.protocol.model.{Block, ModelGen}
import org.alephium.util.AlephiumSpec

class ForksTreeSpec extends AlephiumSpec {

  behavior of "ForksTree"

  trait Fixture {
    val genesis   = Genesis.block
    val forkstree = ForksTree(genesis)
    val blockGen  = ModelGen.blockGenWith(Seq(genesis.hash))
    val chainGen  = ModelGen.chainGen(4, genesis)
  }

  it should "add block correctly" in new Fixture {
    forkstree.numBlocks is 1
    forAll(blockGen, minSuccessful(1)) { block =>
      val blocksSize1 = forkstree.numBlocks
      val txSize1     = forkstree.numTransactions
      forkstree.add(block, 0)
      val blocksSize2 = forkstree.numBlocks
      val txSize2     = forkstree.numTransactions
      blocksSize1 + 1 is blocksSize2
      txSize1 + block.transactions.length is txSize2
    }
  }

  it should "add blocks correctly" in new Fixture {
    forAll(chainGen, minSuccessful(1)) { blocks =>
      val blocksSize1 = forkstree.numBlocks
      val txSize1     = forkstree.numTransactions
      blocks.foreach(block => forkstree.add(block, 0))
      val blocksSize2 = forkstree.numBlocks
      val txSize2     = forkstree.numTransactions
      blocksSize1 + blocks.size is blocksSize2
      txSize1 + blocks.map(_.transactions.length).sum is txSize2

      checkConfirmedBlocks(forkstree, blocks)
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
      blockPool.getBlockSlice(headBlock) is Seq(headBlock)
      blockPool.getBlockSlice(lastBlock) is genesis +: blocks
      blockPool.isTip(headBlock) is false
      blockPool.isTip(lastBlock) is true
      blockPool.getBestTip is lastBlock.hash
      blockPool.getBestChain is genesis +: blocks
      blockPool.maxHeight is blocks.size
      blockPool.getAllTips is Seq(lastBlock.hash)
      checkConfirmedBlocks(blockPool, blocks)
    }
  }

  it should "work correctly with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis), minSuccessful(1)) { longChain =>
      forAll(ModelGen.chainGen(2, genesis), minSuccessful(1)) { shortChain =>
        val blockPool = ForksTree(genesis, 0, 0)

        shortChain.foreach(block => blockPool.add(block, 0))
        blockPool.getHeight(shortChain.head) is 1
        blockPool.getHeight(shortChain.last) is shortChain.size
        blockPool.getBlockSlice(shortChain.head) is Seq(genesis, shortChain.head)
        blockPool.getBlockSlice(shortChain.last) is genesis +: shortChain
        blockPool.isTip(shortChain.head) is false
        blockPool.isTip(shortChain.last) is true

        longChain.init.foreach(block => blockPool.add(block, 0))
        blockPool.maxHeight is longChain.size - 1
        blockPool.getAllTips.toSet is Set(longChain.init.last.hash, shortChain.last.hash)

        blockPool.add(longChain.last, 0)
        blockPool.getHeight(longChain.head) is 1
        blockPool.getHeight(longChain.last) is longChain.size
        blockPool.getBlockSlice(longChain.head) is Seq(genesis, longChain.head)
        blockPool.getBlockSlice(longChain.last) is genesis +: longChain
        blockPool.isTip(longChain.head) is false
        blockPool.isTip(longChain.last) is true
        blockPool.getBestTip is longChain.last.hash
        blockPool.getBestChain is genesis +: longChain
        blockPool.maxHeight is longChain.size
        blockPool.getAllTips.toSet is Set(longChain.last.hash)
      }
    }
  }

  it should "compute correct weights for a single chain" in {
    forAll(ModelGen.chainGen(5), minSuccessful(1)) { blocks =>
      val forksTree = createForksTree(blocks.init)
      blocks.init.foreach(block => forksTree.contains(block) is true)
      forksTree.maxHeight is 3
      forksTree.maxWeight is 6
      forksTree.add(blocks.last, 8)
      forksTree.contains(blocks.last) is true
      forksTree.maxHeight is 4
      forksTree.maxWeight is 8
    }
  }

  it should "compute corrent weights for two chains with same root" in {
    forAll(ModelGen.chainGen(5), minSuccessful(1)) { blocks1 =>
      forAll(ModelGen.chainGen(1, blocks1.head), minSuccessful(1)) { blocks2 =>
        val forksTree = createForksTree(blocks1)
        blocks2.foreach(block => forksTree.add(block, 0))
        blocks1.foreach(block => forksTree.contains(block) is true)
        blocks2.foreach(block => forksTree.contains(block) is true)
        forksTree.maxHeight is 4
        forksTree.maxWeight is 8
        forksTree.getBlocks(blocks1.head.hash).size is 5
        forksTree.getBlocks(blocks2.head.hash).size is 0
        forksTree.getBlocks(blocks1.tail.head.hash).size is 3
      }
    }
  }

  def checkConfirmedBlocks(blockPool: SingleChain, newBlocks: Seq[Block]): Unit = {
    newBlocks.indices.foreach { index =>
      val height   = index + 1
      val blockOpt = blockPool.getConfirmedBlock(height)
      if (height + Consensus.blockConfirmNum <= newBlocks.size) {
        blockOpt.get is newBlocks(index)
      } else {
        blockOpt.isEmpty
      }
    }
    blockPool.getConfirmedBlock(-1).isEmpty
    ()
  }

  def createForksTree(blocks: Seq[Block]): ForksTree = {
    assert(blocks.nonEmpty)
    val forksTree = ForksTree(blocks.head, 0, 0)
    blocks.zipWithIndex.tail foreach {
      case (block, index) =>
        forksTree.add(block, index * 2)
    }
    forksTree
  }
}
