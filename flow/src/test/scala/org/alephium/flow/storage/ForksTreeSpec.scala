package org.alephium.flow.storage

import org.alephium.flow.constant.{Consensus, Genesis}
import org.alephium.protocol.model.{Block, ModelGen}
import org.alephium.util.{AVector, AlephiumSpec}

class ForksTreeSpec extends AlephiumSpec {

  behavior of "ForksTree"

  trait Fixture {
    val genesis   = Genesis.block
    val forkstree = ForksTree(genesis)
    val blockGen  = ModelGen.blockGenWith(AVector(genesis.hash))
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
      blocksSize1 + blocks.length is blocksSize2
      txSize1 + blocks.sumBy(_.transactions.length) is txSize2

      checkConfirmedBlocks(forkstree, blocks)
    }
  }

  it should "work correctly for a chain of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis), minSuccessful(1)) { blocks =>
      val blockPool = ForksTree(genesis, 0, 0)
      blocks.foreach(block => blockPool.add(block, 0))
      val headBlock = genesis
      val lastBlock = blocks.last
      val chain     = AVector(genesis) ++ blocks

      blockPool.getHeight(headBlock) is 0
      blockPool.getHeight(lastBlock) is blocks.length
      blockPool.getBlockSlice(headBlock) is AVector(headBlock)
      blockPool.getBlockSlice(lastBlock) is chain
      blockPool.isTip(headBlock) is false
      blockPool.isTip(lastBlock) is true
      blockPool.getBestTip is lastBlock.hash
      blockPool.getBestBlockChain is chain
      blockPool.maxHeight is blocks.length
      blockPool.getAllTips is AVector(lastBlock.hash)
      checkConfirmedBlocks(blockPool, blocks)
    }
  }

  it should "work correctly with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis), minSuccessful(1)) { longChain =>
      forAll(ModelGen.chainGen(2, genesis), minSuccessful(1)) { shortChain =>
        val blockPool = ForksTree(genesis, 0, 0)

        shortChain.foreach(block => blockPool.add(block, 0))
        blockPool.getHeight(shortChain.head) is 1
        blockPool.getHeight(shortChain.last) is shortChain.length
        blockPool.getBlockSlice(shortChain.head) is AVector(genesis, shortChain.head)
        blockPool.getBlockSlice(shortChain.last) is AVector(genesis) ++ shortChain
        blockPool.isTip(shortChain.head) is false
        blockPool.isTip(shortChain.last) is true

        longChain.init.foreach(block => blockPool.add(block, 0))
        blockPool.maxHeight is longChain.length - 1
        blockPool.getAllTips.toIterable.toSet is Set(longChain.init.last.hash, shortChain.last.hash)

        blockPool.add(longChain.last, 0)
        blockPool.getHeight(longChain.head) is 1
        blockPool.getHeight(longChain.last) is longChain.length
        blockPool.getBlockSlice(longChain.head) is AVector(genesis, longChain.head)
        blockPool.getBlockSlice(longChain.last) is AVector(genesis) ++ longChain
        blockPool.isTip(longChain.head) is false
        blockPool.isTip(longChain.last) is true
        blockPool.getBestTip is longChain.last.hash
        blockPool.getBestBlockChain is AVector(genesis) ++ longChain
        blockPool.maxHeight is longChain.length
        blockPool.getAllTips.toIterable.toSet is Set(longChain.last.hash)
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
        forksTree.getBlocks(blocks1.head.hash).length is 5
        forksTree.getBlocks(blocks2.head.hash).length is 0
        forksTree.getBlocks(blocks1.tail.head.hash).length is 3
      }
    }
  }

  def checkConfirmedBlocks(blockPool: BlockChain, newBlocks: AVector[Block]): Unit = {
    newBlocks.indices.foreach { index =>
      val height   = index + 1
      val blockOpt = blockPool.getConfirmedBlock(height)
      if (height + Consensus.blockConfirmNum <= newBlocks.length) {
        blockOpt.get is newBlocks(index)
      } else {
        blockOpt.isEmpty
      }
    }
    blockPool.getConfirmedBlock(-1).isEmpty
    ()
  }

  def createForksTree(blocks: AVector[Block]): ForksTree = {
    assert(blocks.nonEmpty)
    val forksTree = ForksTree(blocks.head, 0, 0)
    blocks.toIterable.zipWithIndex.tail foreach {
      case (block, index) =>
        forksTree.add(block, index * 2)
    }
    forksTree
  }
}
