package org.alephium.flow.core

import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model.{Block, ModelGen}
import org.alephium.util.AVector

class BlockChainSpec extends AlephiumFlowSpec {
  trait Fixture {
    val genesis  = Block.genesis(AVector.empty, config.maxMiningTarget, 0)
    val blockGen = ModelGen.blockGenWith(AVector.fill(config.depsNum)(genesis.hash))
    val chainGen = ModelGen.chainGen(4, genesis)
  }

  it should "add block correctly" in new Fixture {
    forAll(blockGen) { block =>
      val chain = BlockChain.fromGenesisUnsafe(genesis)
      chain.numHashes is 1
      val blocksSize1 = chain.numHashes
      chain.add(block, 0).isRight is true
      val blocksSize2 = chain.numHashes
      blocksSize1 + 1 is blocksSize2

      val diff = chain.calHashDiff(block.hash, genesis.hash)
      diff.toAdd is AVector(block.hash)
      diff.toRemove.isEmpty is true
    }
  }

  it should "add blocks correctly" in new Fixture {
    forAll(chainGen) { blocks =>
      val chain       = BlockChain.fromGenesisUnsafe(genesis)
      val blocksSize1 = chain.numHashes
      blocks.foreach(block => chain.add(block, 0).isRight is true)
      val blocksSize2 = chain.numHashes
      blocksSize1 + blocks.length is blocksSize2

      val midHashes = chain.getBlockHashesBetween(blocks.last.hash, blocks.head.hash)
      val expected  = blocks.tail.map(_.hash)
      midHashes is expected

      checkConfirmedBlocks(chain, blocks)
    }
  }

  it should "work correctly for a chain of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis)) { blocks =>
      val chain0 = BlockChain.fromGenesisUnsafe(genesis)
      blocks.foreach(block => chain0.add(block, 0))
      val headBlock = genesis
      val lastBlock = blocks.last
      val chain     = AVector(genesis) ++ blocks

      chain0.getHeight(headBlock) is 0
      chain0.getHeight(lastBlock) is blocks.length
      chain0.getBlockSlice(headBlock).right.value is AVector(headBlock)
      chain0.getBlockSlice(lastBlock).right.value is chain
      chain0.isTip(headBlock) is false
      chain0.isTip(lastBlock) is true
      chain0.getBestTip is lastBlock.hash
      chain0.maxHeight is blocks.length
      chain0.getAllTips is AVector(lastBlock.hash)
      checkConfirmedBlocks(chain0, blocks)

      val diff = chain0.calHashDiff(blocks.last.hash, genesis.hash)
      diff.toRemove.isEmpty is true
      diff.toAdd is blocks.map(_.hash)
    }
  }

  it should "work correctly with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis)) { longChain =>
      forAll(ModelGen.chainGen(2, genesis)) { shortChain =>
        val chain0 = BlockChain.fromGenesisUnsafe(genesis)

        shortChain.foreach(block => chain0.add(block, 0))
        chain0.getHeight(shortChain.head) is 1
        chain0.getHeight(shortChain.last) is shortChain.length
        chain0.getBlockSlice(shortChain.head).right.value is AVector(genesis, shortChain.head)
        chain0.getBlockSlice(shortChain.last).right.value is AVector(genesis) ++ shortChain
        chain0.isTip(shortChain.head) is false
        chain0.isTip(shortChain.last) is true

        longChain.init.foreach(block => chain0.add(block, 0))
        chain0.maxHeight is longChain.length - 1
        chain0.getAllTips.toIterable.toSet is Set(longChain.init.last.hash, shortChain.last.hash)

        chain0.add(longChain.last, 0)
        chain0.getHeight(longChain.head) is 1
        chain0.getHeight(longChain.last) is longChain.length
        chain0.getBlockSlice(longChain.head).right.value is AVector(genesis, longChain.head)
        chain0.getBlockSlice(longChain.last).right.value is AVector(genesis) ++ longChain
        chain0.isTip(longChain.head) is false
        chain0.isTip(longChain.last) is true
        chain0.getBestTip is longChain.last.hash
        chain0.maxHeight is longChain.length
        chain0.getAllTips.toIterable.toSet is Set(longChain.last.hash)
      }
    }
  }

  it should "test chain diffs with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis)) { longChain =>
      forAll(ModelGen.chainGen(3, genesis)) { shortChain =>
        val chain = BlockChain.fromGenesisUnsafe(genesis)
        shortChain.foreach(block => chain.add(block, 0))
        longChain.foreach(block  => chain.add(block, 0))

        val diff0 = chain.calHashDiff(longChain.last.hash, shortChain.last.hash)
        diff0.toRemove is shortChain.map(_.hash).reverse
        diff0.toAdd is longChain.map(_.hash)

        val diff1 = chain.calHashDiff(shortChain.last.hash, longChain.last.hash)
        diff1.toRemove is longChain.map(_.hash).reverse
        diff1.toAdd is shortChain.map(_.hash)
      }
    }
  }

  it should "compute correct weights for a single chain" in {
    forAll(ModelGen.chainGen(5)) { blocks =>
      val chain = createBlockChain(blocks.init)
      blocks.init.foreach(block => chain.contains(block) is true)
      chain.maxHeight is 3
      chain.maxWeight is 6
      chain.add(blocks.last, 8)
      chain.contains(blocks.last) is true
      chain.maxHeight is 4
      chain.maxWeight is 8
    }
  }

  it should "compute corrent weights for two chains with same root" in {
    forAll(ModelGen.chainGen(5)) { blocks1 =>
      forAll(ModelGen.chainGen(1, blocks1.head)) { blocks2 =>
        val chain = createBlockChain(blocks1)
        blocks2.foreach(block => chain.add(block, 0))
        blocks1.foreach(block => chain.contains(block) is true)
        blocks2.foreach(block => chain.contains(block) is true)
        chain.maxHeight is 4
        chain.maxWeight is 8
        chain.getHashesAfter(blocks1.head.hash) is {
          val branch1 = blocks1.tail
          AVector(branch1.head.hash) ++ blocks2.map(_.hash) ++ branch1.tail.map(_.hash)
        }
        chain.getHashesAfter(blocks2.head.hash).length is 0
        chain.getHashesAfter(blocks1.tail.head.hash) is blocks1.tail.tail.map(_.hash)
      }
    }
  }

  def checkConfirmedBlocks(chain: BlockChain, newBlocks: AVector[Block]): Unit = {
    newBlocks.indices.foreach { index =>
      val height    = index + 1
      val headerOpt = chain.getConfirmedHeader(height).right.value
      if (height + config.blockConfirmNum <= newBlocks.length) {
        headerOpt.get is newBlocks(index).header
      } else {
        headerOpt.isEmpty
      }
    }
  }

  def createBlockChain(blocks: AVector[Block]): BlockChain = {
    assert(blocks.nonEmpty)
    val chain = BlockChain.fromGenesisUnsafe(blocks.head)
    blocks.toIterable.zipWithIndex.tail foreach {
      case (block, index) =>
        chain.add(block, index * 2)
    }
    chain
  }
}
