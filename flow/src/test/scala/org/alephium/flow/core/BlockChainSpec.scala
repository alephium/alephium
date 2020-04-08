package org.alephium.flow.core

import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.io.HashTreeTipsDB
import org.alephium.protocol.model.{Block, ChainIndex, ModelGen}
import org.alephium.util.AVector

class BlockChainSpec extends AlephiumFlowSpec {
  trait Fixture {
    val genesis  = Block.genesis(AVector.empty, config.maxMiningTarget, 0)
    val blockGen = ModelGen.blockGenWith(AVector.fill(config.depsNum)(genesis.hash))
    val chainGen = ModelGen.chainGen(4, genesis)

    val tipsDB: HashTreeTipsDB =
      config.storages.nodeStateStorage.hashTreeTipsDB(ChainIndex.unsafe(0, 0))
  }

  it should "add block correctly" in new Fixture {
    forAll(blockGen) { block =>
      val chain = BlockChain.fromGenesisUnsafe(genesis, tipsDB)
      chain.numHashes is 1
      val blocksSize1 = chain.numHashes
      chain.add(block, 0).isRight is true
      val blocksSize2 = chain.numHashes
      blocksSize1 + 1 is blocksSize2

      val diff = chain.calHashDiff(block.hash, genesis.hash).right.value
      diff.toAdd is AVector(block.hash)
      diff.toRemove.isEmpty is true
    }
  }

  it should "add blocks correctly" in new Fixture {
    forAll(chainGen) { blocks =>
      val chain       = BlockChain.fromGenesisUnsafe(genesis, tipsDB)
      val blocksSize1 = chain.numHashes
      blocks.foreach(block => chain.add(block, 0).isRight is true)
      val blocksSize2 = chain.numHashes
      blocksSize1 + blocks.length is blocksSize2

      val midHashes = chain.getBlockHashesBetween(blocks.last.hash, blocks.head.hash)
      val expected  = blocks.tail.map(_.hash)
      midHashes isE expected
    }
  }

  it should "work correctly for a chain of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis)) { blocks =>
      val chain0 = BlockChain.fromGenesisUnsafe(genesis, tipsDB)
      blocks.foreach(block => chain0.add(block, 0))
      val headBlock = genesis
      val lastBlock = blocks.last
      val chain     = AVector(genesis) ++ blocks

      chain0.getHeight(headBlock) isE 0
      chain0.getHeight(lastBlock) isE blocks.length
      chain0.getBlockSlice(headBlock).right.value is AVector(headBlock)
      chain0.getBlockSlice(lastBlock).right.value is chain
      chain0.isTip(headBlock) is false
      chain0.isTip(lastBlock) is true
      chain0.getBestTipUnsafe is lastBlock.hash
      chain0.maxHeight isE blocks.length
      chain0.getAllTips is AVector(lastBlock.hash)

      val diff = chain0.calHashDiff(blocks.last.hash, genesis.hash).right.value
      diff.toRemove.isEmpty is true
      diff.toAdd is blocks.map(_.hash)
    }
  }

  it should "work correctly with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis)) { longChain =>
      forAll(ModelGen.chainGen(2, genesis)) { shortChain =>
        val chain0 = BlockChain.fromGenesisUnsafe(genesis, tipsDB)

        shortChain.foreach(block => chain0.add(block, 0))
        chain0.getHeight(shortChain.head) isE 1
        chain0.getHeight(shortChain.last) isE shortChain.length
        chain0.getBlockSlice(shortChain.head).right.value is AVector(genesis, shortChain.head)
        chain0.getBlockSlice(shortChain.last).right.value is AVector(genesis) ++ shortChain
        chain0.isTip(shortChain.head) is false
        chain0.isTip(shortChain.last) is true

        longChain.init.foreach(block => chain0.add(block, 0))
        chain0.maxHeight isE longChain.length - 1
        chain0.getAllTips.toIterable.toSet is Set(longChain.init.last.hash, shortChain.last.hash)

        chain0.add(longChain.last, 0)
        chain0.getHeight(longChain.head) isE 1
        chain0.getHeight(longChain.last) isE longChain.length
        chain0.getBlockSlice(longChain.head).right.value is AVector(genesis, longChain.head)
        chain0.getBlockSlice(longChain.last).right.value is AVector(genesis) ++ longChain
        chain0.isTip(longChain.head) is false
        chain0.isTip(longChain.last) is true
        chain0.getBestTipUnsafe is longChain.last.hash
        chain0.maxHeight isE longChain.length
        chain0.getAllTips.toIterable.toSet is Set(longChain.last.hash, shortChain.last.hash)
      }
    }
  }

  it should "test chain diffs with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis)) { longChain =>
      forAll(ModelGen.chainGen(3, genesis)) { shortChain =>
        val chain = BlockChain.fromGenesisUnsafe(genesis, tipsDB)
        shortChain.foreach(block => chain.add(block, 0))
        longChain.foreach(block  => chain.add(block, 0))

        val diff0 = chain.calHashDiff(longChain.last.hash, shortChain.last.hash).right.value
        diff0.toRemove is shortChain.map(_.hash).reverse
        diff0.toAdd is longChain.map(_.hash)

        val diff1 = chain.calHashDiff(shortChain.last.hash, longChain.last.hash).right.value
        diff1.toRemove is longChain.map(_.hash).reverse
        diff1.toAdd is shortChain.map(_.hash)
      }
    }
  }

  it should "compute correct weights for a single chain" in new Fixture {
    forAll(ModelGen.chainGen(5)) { blocks =>
      val chain = createBlockChain(blocks.init, tipsDB)
      blocks.init.foreach(block => chain.contains(block) isE true)
      chain.maxHeight isE 3
      chain.maxWeight isE 6
      chain.add(blocks.last, 8)
      chain.contains(blocks.last) isE true
      chain.maxHeight isE 4
      chain.maxWeight isE 8
    }
  }

  it should "compute corrent weights for two chains with same root" in new Fixture {
    forAll(ModelGen.chainGen(5)) { blocks1 =>
      forAll(ModelGen.chainGen(1, blocks1.head)) { blocks2 =>
        val chain = createBlockChain(blocks1, tipsDB)
        blocks2.foreach(block => chain.add(block, 0))
        blocks1.foreach(block => chain.contains(block) isE true)
        blocks2.foreach(block => chain.contains(block) isE true)
        chain.maxHeight isE 4
        chain.maxWeight isE 8
        chain.getHashesAfter(blocks1.head.hash) isE {
          val branch1 = blocks1.tail
          AVector(branch1.head.hash) ++ blocks2.map(_.hash) ++ branch1.tail.map(_.hash)
        }
        chain.getHashesAfter(blocks2.head.hash).map(_.length) isE 0
        chain.getHashesAfter(blocks1.tail.head.hash) isE blocks1.tail.tail.map(_.hash)
      }
    }
  }

  def createBlockChain(blocks: AVector[Block], tipsDB: HashTreeTipsDB): BlockChain = {
    assert(blocks.nonEmpty)
    val chain = BlockChain.fromGenesisUnsafe(blocks.head, tipsDB)
    blocks.toIterable.zipWithIndex.tail foreach {
      case (block, index) =>
        chain.add(block, index * 2)
    }
    chain
  }
}
