package org.alephium.flow.core

import org.scalatest.EitherValues._

import org.alephium.crypto.Keccak256
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.io.RocksDBSource.Settings
import org.alephium.flow.io.Storages
import org.alephium.protocol.ALF
import org.alephium.protocol.model.{Block, ChainIndex, ModelGen}
import org.alephium.util.AVector

class BlockChainSpec extends AlephiumFlowSpec {
  trait Fixture {
    val genesis  = Block.genesis(AVector.empty, config.maxMiningTarget, 0)
    val blockGen = ModelGen.blockGenWith(AVector.fill(config.depsNum)(genesis.hash))
    val chainGen = ModelGen.chainGen(4, genesis)

    def buildBlockChain(genesisBlock: Block = genesis): BlockChain = {
      val storages =
        Storages.createUnsafe(rootPath, "db", Keccak256.random.toHexString, Settings.syncWrite)
      BlockChain.createUnsafe(ChainIndex.unsafe(0, 0), genesisBlock, storages)
    }

    def createBlockChain(blocks: AVector[Block]): BlockChain = {
      assert(blocks.nonEmpty)
      val chain = buildBlockChain(blocks.head)
      blocks.toIterable.zipWithIndex.tail foreach {
        case (block, index) =>
          chain.add(block, index * 2)
      }
      chain
    }
  }

  it should "add block correctly" in new Fixture {
    forAll(blockGen) { block =>
      val chain = buildBlockChain()
      chain.numHashes is 1
      val blocksSize1 = chain.numHashes
      chain.add(block, 1).isRight is true
      val blocksSize2 = chain.numHashes
      blocksSize1 + 1 is blocksSize2

      chain.getHeightUnsafe(block.hash) is (ALF.GenesisHeight + 1)
      chain.getHeight(block.hash).right.value is (ALF.GenesisHeight + 1)

      val diff = chain.calHashDiff(block.hash, genesis.hash).right.value
      diff.toAdd is AVector(block.hash)
      diff.toRemove.isEmpty is true
    }
  }

  it should "add blocks correctly" in new Fixture {
    forAll(chainGen) { blocks =>
      val chain       = buildBlockChain()
      val blocksSize1 = chain.numHashes
      blocks.foreachWithIndex((block, index) => chain.add(block, index + 1).isRight is true)
      val blocksSize2 = chain.numHashes
      blocksSize1 + blocks.length is blocksSize2

      val midHashes = chain.getBlockHashesBetween(blocks.last.hash, blocks.head.hash)
      val expected  = blocks.tail.map(_.hash)
      midHashes isE expected
    }
  }

  it should "work correctly for a chain of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis)) { blocks =>
      val chain = buildBlockChain()
      blocks.foreachWithIndex((block, index) => chain.add(block, index + 1).isRight is true)
      val headBlock     = genesis
      val lastBlock     = blocks.last
      val chainExpected = AVector(genesis) ++ blocks

      chain.getHeight(headBlock) isE 0
      chain.getHeight(lastBlock) isE blocks.length
      chain.getBlockSlice(headBlock).right.value is AVector(headBlock)
      chain.getBlockSlice(lastBlock).right.value is chainExpected
      chain.isTip(headBlock) is false
      chain.isTip(lastBlock) is true
      chain.getBestTipUnsafe is lastBlock.hash
      chain.maxHeight isE blocks.length
      chain.getAllTips is AVector(lastBlock.hash)

      val diff = chain.calHashDiff(blocks.last.hash, genesis.hash).right.value
      diff.toRemove.isEmpty is true
      diff.toAdd is blocks.map(_.hash)
    }
  }

  it should "work correctly with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis)) { longChain =>
      forAll(ModelGen.chainGen(2, genesis)) { shortChain =>
        val chain = buildBlockChain()

        shortChain.foreachWithIndex((block, index) => chain.add(block, index + 1))
        chain.getHeight(shortChain.head) isE 1
        chain.getHeight(shortChain.last) isE shortChain.length
        chain.getBlockSlice(shortChain.head).right.value is AVector(genesis, shortChain.head)
        chain.getBlockSlice(shortChain.last).right.value is AVector(genesis) ++ shortChain
        chain.isTip(shortChain.head) is false
        chain.isTip(shortChain.last) is true

        longChain.init.foreachWithIndex((block, index) => chain.add(block, index + 1))
        chain.maxHeight isE longChain.length - 1
        chain.getAllTips.toIterable.toSet is Set(longChain.init.last.hash, shortChain.last.hash)

        chain.add(longChain.last, longChain.length)
        chain.getHeight(longChain.head) isE 1
        chain.getHeight(longChain.last) isE longChain.length
        chain.getBlockSlice(longChain.head).right.value is AVector(genesis, longChain.head)
        chain.getBlockSlice(longChain.last).right.value is AVector(genesis) ++ longChain
        chain.isTip(longChain.head) is false
        chain.isTip(longChain.last) is true
        chain.getBestTipUnsafe is longChain.last.hash
        chain.maxHeight isE longChain.length
        chain.getAllTips.toIterable.toSet is Set(longChain.last.hash, shortChain.last.hash)
      }
    }
  }

  it should "test chain diffs with two chains of blocks" in new Fixture {
    forAll(ModelGen.chainGen(4, genesis)) { longChain =>
      forAll(ModelGen.chainGen(3, genesis)) { shortChain =>
        val chain = buildBlockChain()
        shortChain.foreachWithIndex((block, index) => chain.add(block, index + 1))
        longChain.foreachWithIndex((block, index)  => chain.add(block, index + 1))

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
      val chain = createBlockChain(blocks.init)
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
        val chain = createBlockChain(blocks1)
        blocks2.foreachWithIndex((block, index) => chain.add(block, index + 1).isRight is true)

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
}
