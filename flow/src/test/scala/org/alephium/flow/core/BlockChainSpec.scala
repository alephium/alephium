// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.core

import java.math.BigInteger

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import org.scalatest.BeforeAndAfter
import org.scalatest.EitherValues._

import org.alephium.flow.core.BlockChain.{TxIndex, TxIndexes, TxStatus}
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.io.IOError
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, Bytes, Duration, TimeStamp}

// scalastyle:off file.size.limit
class BlockChainSpec extends AlephiumSpec with BeforeAndAfter {
  trait Fixture extends AlephiumConfigFixture with NoIndexModelGeneratorsLike {
    lazy val genesis =
      Block.genesis(ChainIndex.unsafe(0, 0), AVector.empty)(brokerConfig, consensusConfigs.mainnet)
    lazy val blockGen0 = blockGenOf(AVector.fill(brokerConfig.depsNum)(genesis.hash), Hash.zero)
    lazy val chainGen  = chainGenOf(4, genesis)

    def buildBlockChain(genesisBlock: Block = genesis): BlockChain = {
      val storages = StoragesFixture.buildStorages(rootPath)
      BlockChain.createUnsafe(genesisBlock, storages, BlockChain.initializeGenesis(genesisBlock)(_))
    }

    def createBlockChain(blocks: AVector[Block]): BlockChain = {
      assume(blocks.nonEmpty)
      val chain = buildBlockChain(blocks.head)
      blocks.tail foreach { block =>
        val parentWeight = chain.getWeightUnsafe(block.parentHash)
        chain.add(block, parentWeight + block.weight).isRight is true
      }
      chain
    }

    def addBlock(chain: BlockChain, block: Block): Unit = {
      addBlocks(chain, AVector(block))
    }

    def addBlocks(chain: BlockChain, blocks: AVector[Block]): Unit = {
      blocks.foreach { block =>
        val parentWeight = chain.getWeightUnsafe(block.parentHash)
        chain.add(block, parentWeight + block.weight).isRight is true
      }
    }

    def addChain(chain: BlockChain, blockNum: Int) = {
      val blocks = chainGenOf(chain.chainIndex, blockNum, genesis.hash, TimeStamp.now()).sample.get
      addBlocks(chain, blocks)
      chain.maxHeightUnsafe is blockNum
      chain.maxHeightByWeightUnsafe is blockNum
      blocks
    }

    def addMaxWeightBlock(chain: BlockChain) = {
      val maxWeight = chain.maxWeight.rightValue + Weight(BigInteger.ONE)
      val block     = blockGen(chain.chainIndex, TimeStamp.now(), genesis.hash).sample.get
      chain.add(block, maxWeight).isRight is true
      chain.maxHeightUnsafe isnot chain.maxHeightByWeightUnsafe
      chain.maxHeightByWeightUnsafe is 1
      block
    }
  }

  it should "initialize genesis correctly" in new Fixture {
    val chain = buildBlockChain()
    chain.contains(genesis) isE true
    chain.getHeight(genesis.hash) isE ALPH.GenesisHeight
    chain.getWeight(genesis.hash) isE ALPH.GenesisWeight
    chain.containsUnsafe(genesis.hash) is true
    chain.getHeightUnsafe(genesis.hash) is ALPH.GenesisHeight
    chain.getWeightUnsafe(genesis.hash) is ALPH.GenesisWeight
    chain.getTimestamp(genesis.hash) isE ALPH.GenesisTimestamp
    chain.getTimestampUnsafe(genesis.hash) is ALPH.GenesisTimestamp
  }

  it should "validate block height" in new Fixture {
    val chain              = buildBlockChain()
    val maxForkDepth       = 5
    val deepForkedBlock    = chainGenOf(1, genesis).sample.get.head
    val mainChainPart1     = chainGenOf(4, genesis).sample.get
    val validBlock         = chainGenOf(1, mainChainPart1.last).sample.get.head
    val blockWithoutParent = chainGenOf(1, validBlock).sample.get.head
    val mainChainPart2     = chainGenOf(4, mainChainPart1.last).sample.get

    addBlocks(chain, mainChainPart1)
    chain.maxHeightUnsafe is 4
    chain.validateBlockHeight(deepForkedBlock, maxForkDepth) isE true
    addBlocks(chain, mainChainPart2)
    chain.maxHeightUnsafe is 8
    chain.validateBlockHeight(deepForkedBlock, maxForkDepth) isE false
    chain.validateBlockHeight(validBlock, maxForkDepth) isE true
    chain.contains(validBlock) isE false
    chain
      .validateBlockHeight(blockWithoutParent, maxForkDepth)
      .leftValue is a[IOError.KeyNotFound]
  }

  it should "add block correctly" in new Fixture {
    val block = blockGen0.sample.get
    val chain = buildBlockChain()
    chain.numHashes is 1
    val blocksSize1 = chain.numHashes
    addBlock(chain, block)
    val blocksSize2 = chain.numHashes
    blocksSize1 + 1 is blocksSize2

    chain.getHeightUnsafe(block.hash) is (ALPH.GenesisHeight + 1)
    chain.getHeight(block.hash) isE (ALPH.GenesisHeight + 1)
    chain.getTimestamp(block.hash) isE block.timestamp
    chain.getTimestampUnsafe(block.hash) is block.timestamp

    val diff = chain.calHashDiff(block.hash, genesis.hash).rightValue
    diff.toAdd is AVector(block.hash)
    diff.toRemove.isEmpty is true
  }

  it should "persiste txs" in new Fixture {
    val chain = buildBlockChain()
    forAll(blockGen0) { block0 =>
      chain.add(block0, Weight(1)).isRight is true
      block0.transactions.foreachWithIndex { case (tx, index) =>
        val txIndex = TxIndex(block0.hash, index)
        if (brokerConfig.contains(block0.chainIndex.from)) {
          chain.txStorage.get(tx.id) isE TxIndexes(AVector(txIndex))
        } else {
          chain.txStorage.exists(tx.id) isE false
        }
      }
      val block1 = block0.copy(header = block0.header.copy(nonce = Nonce.secureRandom()))
      chain.add(block1, Weight(1)).isRight is true
      block1.transactions.foreachWithIndex { case (tx, index) =>
        val txIndex0 = TxIndex(block0.hash, index)
        val txIndex1 = TxIndex(block1.hash, index)
        (
          brokerConfig.contains(block0.chainIndex.from),
          brokerConfig.contains(block1.chainIndex.from)
        ) match {
          case (true, true) =>
            chain.txStorage.get(tx.id) isE TxIndexes(AVector(txIndex0, txIndex1))
          case (true, false) =>
            chain.txStorage.get(tx.id) isE TxIndexes(AVector(txIndex0))
          case (false, true) =>
            chain.txStorage.get(tx.id) isE TxIndexes(AVector(txIndex1))
          case (false, false) =>
            chain.txStorage.exists(tx.id) isE false
        }
      }
    }
  }

  it should "return correct tx status for forks 0" in new Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val shortChain = chainGenOf(2, genesis).sample.get
    val longChain  = chainGenOf(4, genesis).sample.get
    val chain      = buildBlockChain()

    shortChain.foreach { block =>
      block.transactions.foreach { tx => chain.getTxStatus(tx.id) isE None }
      block.transactions.foreach { tx => chain.getTransaction(tx.id) isE None }
    }
    longChain.foreach { block =>
      block.transactions.foreach { tx => chain.getTxStatus(tx.id) isE None }
      block.transactions.foreach { tx => chain.getTransaction(tx.id) isE None }
    }

    addBlocks(chain, shortChain)
    shortChain.foreachWithIndex { case (block, blockIndex) =>
      block.transactions.foreachWithIndex { case (tx, txIndex) =>
        chain.getTxStatus(tx.id) isE Some(
          TxStatus(TxIndex(block.hash, txIndex), shortChain.length - blockIndex)
        )
        chain.getTransaction(tx.id) isE Some(tx)
      }
    }
    longChain.foreach { block =>
      block.transactions.foreach { tx => chain.getTxStatus(tx.id) isE None }
      block.transactions.foreach { tx => chain.getTransaction(tx.id) isE None }
    }

    addBlocks(chain, longChain)
    shortChain.foreach { block =>
      block.transactions.foreach { tx => chain.getTxStatus(tx.id) isE None }
      block.transactions.foreach { tx => chain.getTransaction(tx.id) isE None }
    }
    longChain.foreachWithIndex { case (block, blockIndex) =>
      block.transactions.foreachWithIndex { case (tx, txIndex) =>
        chain.getTxStatus(tx.id) isE Some(
          TxStatus(TxIndex(block.hash, txIndex), longChain.length - blockIndex)
        )
        chain.getTransaction(tx.id) isE Some(tx)
      }
    }
  }

  it should "return correct tx status for forks 1" in new Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val longChain = chainGenOf(4, genesis).sample.get
    val shortChain = chainGenOf(2, genesis).sample.get.mapWithIndex { case (block, index) =>
      block.copy(transactions = longChain(index).transactions)
    }
    val chain = buildBlockChain()

    shortChain.foreach { block =>
      block.transactions.foreach { tx => chain.getTxStatus(tx.id) isE None }
      block.transactions.foreach { tx => chain.getTransaction(tx.id) isE None }
    }
    longChain.foreach { block =>
      block.transactions.foreach { tx => chain.getTxStatus(tx.id) isE None }
      block.transactions.foreach { tx => chain.getTransaction(tx.id) isE None }
    }

    addBlocks(chain, shortChain)
    shortChain.foreachWithIndex { case (block, blockIndex) =>
      block.transactions.foreachWithIndex { case (tx, txIndex) =>
        chain.getTxStatus(tx.id) isE Some(
          TxStatus(TxIndex(block.hash, txIndex), shortChain.length - blockIndex)
        )
        chain.getTransaction(tx.id) isE Some(tx)
      }
    }
    longChain.foreachWithIndex { case (block, blockIndex) =>
      block.transactions.foreachWithIndex { case (tx, txIndex) =>
        if (blockIndex < shortChain.length) {
          chain.getTxStatus(tx.id) isE Some(
            TxStatus(
              TxIndex(shortChain(blockIndex).hash, txIndex),
              shortChain.length - blockIndex
            )
          )
          chain.getTransaction(tx.id) isE Some(tx)
        } else {
          chain.getTxStatus(tx.id) isE None
          chain.getTransaction(tx.id) isE None
        }
      }
    }

    addBlocks(chain, longChain)
    shortChain.foreachWithIndex { case (block, blockIndex) =>
      block.transactions.foreachWithIndex { case (tx, txIndex) =>
        chain.getTxStatus(tx.id) isE Some(
          TxStatus(TxIndex(longChain(blockIndex).hash, txIndex), longChain.length - blockIndex)
        )
        chain.getTransaction(tx.id) isE Some(tx)
      }
    }
    longChain.foreachWithIndex { case (block, blockIndex) =>
      block.transactions.foreachWithIndex { case (tx, txIndex) =>
        chain.getTxStatus(tx.id) isE Some(
          TxStatus(TxIndex(block.hash, txIndex), longChain.length - blockIndex)
        )
        chain.getTransaction(tx.id) isE Some(tx)
      }
    }
  }

  it should "add blocks correctly" in new Fixture {
    val blocks      = chainGen.sample.get
    val chain       = buildBlockChain()
    val blocksSize1 = chain.numHashes
    addBlocks(chain, blocks)
    val blocksSize2 = chain.numHashes
    blocksSize1 + blocks.length is blocksSize2

    val midHashes = chain.getBlockHashesBetween(blocks.last.hash, blocks.head.hash)
    val expected  = blocks.tail.map(_.hash)
    midHashes isE expected
  }

  it should "work correctly for a chain of blocks" in new Fixture {
    val blocks = chainGenOf(4, genesis).sample.get
    val chain  = buildBlockChain()
    addBlocks(chain, blocks)
    val headBlock     = genesis
    val lastBlock     = blocks.last
    val chainExpected = AVector(genesis) ++ blocks

    chain.getHeight(headBlock) isE ALPH.GenesisHeight
    chain.getHeight(lastBlock) isE blocks.length
    chain.getBlockSlice(headBlock) isE AVector(headBlock)
    chain.getBlockSlice(lastBlock) isE chainExpected
    chain.isTip(headBlock) is false
    chain.isTip(lastBlock) is true
    chain.getBestTipUnsafe() is lastBlock.hash
    chain.maxHeightByWeight isE blocks.length
    chain.getAllTips is AVector(lastBlock.hash)

    val diff = chain.calHashDiff(blocks.last.hash, genesis.hash).rightValue
    diff.toRemove.isEmpty is true
    diff.toAdd is blocks.map(_.hash)
  }

  it should "work correctly with two chains of blocks" in new Fixture {
    val longChain  = chainGenOf(4, genesis).sample.get
    val shortChain = chainGenOf(2, genesis).sample.get
    val chain      = buildBlockChain()

    addBlocks(chain, shortChain)
    chain.getHeight(shortChain.head) isE 1
    chain.getHeight(shortChain.last) isE shortChain.length
    chain.getBlockSlice(shortChain.head) isE AVector(genesis, shortChain.head)
    chain.getBlockSlice(shortChain.last) isE AVector(genesis) ++ shortChain
    chain.isTip(shortChain.head) is false
    chain.isTip(shortChain.last) is true

    addBlocks(chain, longChain.init)
    chain.maxHeightByWeight isE longChain.length - 1
    chain.getAllTips.toIterable.toSet is Set(longChain.init.last.hash, shortChain.last.hash)

    addBlock(chain, longChain.last)
    chain.getHeight(longChain.head) isE 1
    chain.getHeight(longChain.last) isE longChain.length
    chain.getBlockSlice(longChain.head) isE AVector(genesis, longChain.head)
    chain.getBlockSlice(longChain.last) isE AVector(genesis) ++ longChain
    chain.isTip(longChain.head) is false
    chain.isTip(longChain.last) is true
    chain.getBestTipUnsafe() is longChain.last.hash
    chain.maxHeightByWeight isE longChain.length
    chain.getAllTips.toIterable.toSet is Set(longChain.last.hash, shortChain.last.hash)
  }

  it should "check isCanonical" in new Fixture {
    val longChain  = chainGenOf(4, genesis).sample.get
    val shortChain = chainGenOf(2, genesis).sample.get

    val chain = buildBlockChain()
    addBlocks(chain, shortChain)
    shortChain.foreach { block => chain.isCanonicalUnsafe(block.hash) is true }
    longChain.foreach { block => chain.isCanonicalUnsafe(block.hash) is false }

    addBlocks(chain, longChain.init)
    shortChain.foreach { block => chain.isCanonicalUnsafe(block.hash) is false }
    longChain.init.foreach { block => chain.isCanonicalUnsafe(block.hash) is true }
    chain.isCanonicalUnsafe(longChain.last.hash) is false

    addBlock(chain, longChain.last)
    shortChain.foreach { block => chain.isCanonicalUnsafe(block.hash) is false }
    longChain.foreach { block => chain.isCanonicalUnsafe(block.hash) is true }
  }

  it should "update mainchain hash based on heights" in new Fixture {
    val longChain  = chainGenOf(3, genesis).sample.get
    val shortChain = chainGenOf(2, genesis).sample.get

    val chain = buildBlockChain()
    addBlocks(chain, shortChain)
    chain.getHashes(ALPH.GenesisHeight + 1) isE AVector(shortChain(0).hash)
    chain.getHashes(ALPH.GenesisHeight + 2) isE AVector(shortChain(1).hash)

    addBlocks(chain, longChain)
    chain.getHashes(ALPH.GenesisHeight + 1) isE AVector(longChain(0).hash, shortChain(0).hash)
    chain.getHashes(ALPH.GenesisHeight + 2) isE AVector(longChain(1).hash, shortChain(1).hash)
  }

  it should "update mainchain hash when heights of blocks are equal" in new Fixture {
    val chain0 = chainGenOf(2, genesis).sample.get
    val chain1 = chainGenOf(2, genesis).sample.get

    val chain = buildBlockChain()
    addBlocks(chain, chain0)
    chain.getHashes(ALPH.GenesisHeight + 1) isE AVector(chain0(0).hash)
    chain.getHashes(ALPH.GenesisHeight + 2) isE AVector(chain0(1).hash)

    addBlocks(chain, chain1)
    if (chain.blockHashOrdering.compare(chain1(1).hash, chain0(1).hash) > 0) {
      chain.getHashes(ALPH.GenesisHeight + 1) isE AVector(chain1(0).hash, chain0(0).hash)
      chain.getHashes(ALPH.GenesisHeight + 2) isE AVector(chain1(1).hash, chain0(1).hash)
    } else {
      chain.getHashes(ALPH.GenesisHeight + 1) isE AVector(chain0(0).hash, chain1(0).hash)
      chain.getHashes(ALPH.GenesisHeight + 2) isE AVector(chain0(1).hash, chain1(1).hash)
    }
  }

  it should "update mainchain hash based on heights instead of weight" in new Fixture {
    override val configValues = Map(
      ("alephium.consensus.num-zeros-at-least-in-hash", 10)
    )

    val longChain   = chainGenOf(3, genesis).sample.get
    val shortChain0 = chainGenOf(2, genesis).sample.get
    val block0      = shortChain0(0)
    val block1      = shortChain0(1)
    val block00 = block0.copy(header =
      block0.header.copy(target = Target.unsafe(block0.header.target.value.divide(2)))
    )
    val block11 = block1.copy(header =
      block1.header.copy(
        blockDeps = BlockDeps.unsafe(AVector.fill(config.broker.depsNum)(block00.hash)),
        target = Target.unsafe(block1.header.target.value.divide(2))
      )
    )
    val shortChain = AVector(block00, block11)

    val chain = buildBlockChain()
    addBlocks(chain, longChain)
    addBlocks(chain, shortChain)
    chain.getHashes(ALPH.GenesisHeight + 1) isE AVector(longChain(0).hash, shortChain(0).hash)
    chain.getHashes(ALPH.GenesisHeight + 2) isE AVector(longChain(1).hash, shortChain(1).hash)
    chain.getHashes(ALPH.GenesisHeight + 3) isE AVector(longChain(2).hash)
  }

  it should "test chain diffs with two chains of blocks" in new Fixture {
    forAll(chainGenOf(4, genesis)) { longChain =>
      forAll(chainGenOf(3, genesis)) { shortChain =>
        val chain = buildBlockChain()
        addBlocks(chain, shortChain)
        addBlocks(chain, longChain)

        val diff0 = chain.calHashDiff(longChain.last.hash, shortChain.last.hash).rightValue
        diff0.toRemove is shortChain.map(_.hash).reverse
        diff0.toAdd is longChain.map(_.hash)

        val diff1 = chain.calHashDiff(shortChain.last.hash, longChain.last.hash).rightValue
        diff1.toRemove is longChain.map(_.hash).reverse
        diff1.toAdd is shortChain.map(_.hash)
      }
    }
  }

  it should "reorder hashes when there are two branches" in new Fixture {
    val shortChain = chainGenOf(2, genesis).sample.get
    val longChain  = chainGenOf(3, genesis).sample.get
    val chain      = buildBlockChain()
    addBlocks(chain, shortChain)
    chain.getHashes(ALPH.GenesisHeight + 2) isE AVector(shortChain(1).hash)
    chain.hashesCache.get(ALPH.GenesisHeight + 2).value is AVector(shortChain(1).hash)
    chain.maxWeight isE chain.getWeightUnsafe(shortChain.last.hash)
    addBlocks(chain, longChain)
    chain.maxWeight isE chain.getWeightUnsafe(longChain.last.hash)
    chain.getHashes(ALPH.GenesisHeight + 2) isE AVector(longChain(1).hash, shortChain(1).hash)
    chain.hashesCache.get(ALPH.GenesisHeight + 2).value is AVector(
      longChain(1).hash,
      shortChain(1).hash
    )
    chain.getHashes(ALPH.GenesisHeight + 3) isE AVector(longChain.last.hash)
    chain.hashesCache.get(ALPH.GenesisHeight + 3).value is AVector(longChain.last.hash)
  }

  it should "compute correct weights for a single chain" in new Fixture {
    val consensusConfig = consensusConfigs.getConsensusConfig(TimeStamp.now())
    val blocks          = chainGenOf(5).sample.get
    val chain           = createBlockChain(blocks.init)
    blocks.init.foreach(block => chain.contains(block) isE true)
    chain.maxHeightByWeight isE 3
    chain.maxWeight isE consensusConfig.minBlockWeight * 3
    addBlock(chain, blocks.last)
    chain.contains(blocks.last) isE true
    chain.maxHeightByWeight isE 4
    chain.maxWeight isE consensusConfig.minBlockWeight * 4
  }

  it should "compute correct weights for two chains with same root" in new Fixture {
    val consensusConfig = consensusConfigs.getConsensusConfig(TimeStamp.now())
    val blocks1         = chainGenOf(5).sample.get
    val blocks2         = chainGenOf(1, blocks1.head).sample.get
    val chain           = createBlockChain(blocks1)
    addBlocks(chain, blocks2)

    blocks1.foreach(block => chain.contains(block) isE true)
    blocks2.foreach(block => chain.contains(block) isE true)
    chain.maxHeightByWeight isE 4
    chain.maxWeight isE consensusConfig.minBlockWeight * 4
    chain.getHashesAfter(blocks1.head.hash) isE {
      val branch1 = blocks1.tail
      AVector(branch1.head.hash) ++ blocks2.map(_.hash) ++ branch1.tail.map(_.hash)
    }
    chain.getHashesAfter(blocks2.head.hash).map(_.length) isE 0
    chain.getHashesAfter(blocks1.tail.head.hash) isE blocks1.tail.tail.map(_.hash)
  }

  it should "order hash" in new Fixture {
    val hash0 = BlockHash.generate
    val hash1 = BlockHash.generate
    val hash2 = BlockHash.generate
    val chain = buildBlockChain()

    chain.addHash(hash0, BlockHash.zero, 1, Weight(1), TimeStamp.unsafe(1), false)
    chain.addHash(hash1, BlockHash.zero, 1, Weight(1), TimeStamp.unsafe(1), false)
    chain.addHash(hash2, BlockHash.zero, 1, Weight(2), TimeStamp.unsafe(1), false)

    chain.blockHashOrdering.lt(hash0, hash1) is
      Bytes.byteStringOrdering.lt(hash0.bytes, hash1.bytes)
    chain.blockHashOrdering.compare(hash0, hash2) is -1
    chain.blockHashOrdering.compare(hash1, hash2) is -1
    chain.blockHashOrdering.compare(hash0, hash0) is 0
    chain.blockHashOrdering.compare(hash1, hash1) is 0
    chain.blockHashOrdering.compare(hash2, hash2) is 0
  }

  behavior of "Block tree algorithm"

  trait UnforkedFixture extends Fixture {
    val chain  = buildBlockChain()
    val blocks = chainGenOf(2, genesis).sample.get
    addBlocks(chain, blocks)
  }

  it should "test chainBack" in new UnforkedFixture {
    chain.chainBackUntil(genesis.hash, ALPH.GenesisHeight) isE AVector.empty[BlockHash]
    chain.chainBackUntil(blocks.last.hash, ALPH.GenesisHeight) isE blocks.map(_.hash)
    chain.chainBackUntil(blocks.last.hash, ALPH.GenesisHeight + 1) isE blocks.tail.map(_.hash)
    chain.chainBackUntil(blocks.init.last.hash, ALPH.GenesisHeight) isE blocks.init.map(_.hash)
  }

  it should "test getPredecessor" in new UnforkedFixture {
    blocks.foreach { block =>
      chain.getPredecessor(block.hash, ALPH.GenesisHeight) isE genesis.hash
    }
    chain.getPredecessor(blocks.last.hash, ALPH.GenesisHeight + 1) isE blocks.head.hash
  }

  it should "test getBlockHashSlice" in new UnforkedFixture {
    chain.getBlockHashSlice(blocks.last.hash) isE (genesis.hash +: blocks.map(_.hash))
    chain.getBlockHashSlice(blocks.head.hash) isE AVector(genesis.hash, blocks.head.hash)
  }

  trait ForkedFixture extends Fixture {
    val chain  = buildBlockChain()
    val chain0 = chainGenOf(2, genesis).sample.get
    val chain1 = chainGenOf(2, genesis).sample.get
    addBlocks(chain, chain0)
    addBlocks(chain, chain1)
  }

  it should "test getHashesAfter" in new ForkedFixture {
    val allHashes = (chain0 ++ chain1).map(_.hash).toSet
    chain.getHashesAfter(chain0.head.hash) isE chain0.tail.map(_.hash)
    chain.getHashesAfter(chain1.head.hash) isE chain1.tail.map(_.hash)
    chain.getHashesAfter(genesis.hash).rightValue.toSet is allHashes

    chain.getHashesAfter(blockGen0.sample.get.hash) isE AVector.empty[BlockHash]
  }

  it should "test isBefore" in new ForkedFixture {
    chain0.foreach { block =>
      chain.isBefore(genesis.hash, block.hash) isE true
      chain.isBefore(block.hash, genesis.hash) isE false
      chain.isBefore(block.hash, chain1.last.hash) isE false
    }
    chain1.foreach { block =>
      chain.isBefore(genesis.hash, block.hash) isE true
      chain.isBefore(block.hash, genesis.hash) isE false
      chain.isBefore(block.hash, chain0.last.hash) isE false
    }

    val chain2 = chainGenOf(2, chain0.last).sample.get
    addBlocks(chain, chain2)
    chain2.foreach { block =>
      chain.isBefore(genesis.hash, block.hash) isE true
      chain.isBefore(block.hash, chain2.last.hash) isE true
      chain.isBefore(chain0.last.hash, block.hash) isE true
    }
    chain.isBefore(chain1.last.hash, chain2.last.hash) isE false
  }

  it should "test getBlockHashesBetween" in new ForkedFixture {
    chain.getBlockHashesBetween(genesis.hash, genesis.hash) isE AVector.empty[BlockHash]
    chain.getBlockHashesBetween(chain0.head.hash, chain0.head.hash) isE AVector.empty[BlockHash]
    chain.getBlockHashesBetween(chain1.head.hash, chain1.head.hash) isE AVector.empty[BlockHash]

    chain.getBlockHashesBetween(chain0.last.hash, genesis.hash) isE chain0.map(_.hash)
    chain.getBlockHashesBetween(chain0.last.hash, chain0.head.hash) isE chain0.tail.map(_.hash)
    chain.getBlockHashesBetween(chain1.last.hash, genesis.hash) isE chain1.map(_.hash)
    chain.getBlockHashesBetween(chain1.last.hash, chain1.head.hash) isE chain1.tail.map(_.hash)

    chain.getBlockHashesBetween(genesis.hash, chain0.last.hash).left.value is a[IOError.Other]
    chain.getBlockHashesBetween(genesis.hash, chain1.last.hash).left.value is a[IOError.Other]
    chain.getBlockHashesBetween(chain0.head.hash, chain1.head.hash).left.value is a[IOError.Other]
  }

  it should "test calHashDiff" in new ForkedFixture {
    import BlockHashChain.ChainDiff
    val hashes0 = chain0.map(_.hash)
    val hashes1 = chain1.map(_.hash)
    chain.calHashDiff(chain0.last.hash, genesis.hash) isE ChainDiff(AVector.empty, hashes0)
    chain.calHashDiff(genesis.hash, chain0.last.hash) isE ChainDiff(hashes0.reverse, AVector.empty)
    chain.calHashDiff(chain1.last.hash, genesis.hash) isE ChainDiff(AVector.empty, hashes1)
    chain.calHashDiff(genesis.hash, chain1.last.hash) isE ChainDiff(hashes1.reverse, AVector.empty)

    val expected0 = ChainDiff(hashes1.reverse, hashes0)
    val expected1 = ChainDiff(hashes0.reverse, hashes1)
    chain.calHashDiff(chain0.last.hash, chain1.last.hash) isE expected0
    chain.calHashDiff(chain1.last.hash, chain0.last.hash) isE expected1
  }

  it should "test getHeightedBlocks" in new Fixture {
    val longChain = chainGenOf(9, genesis).sample.get
    val chain     = buildBlockChain()
    addBlocks(chain, longChain)
    val all = chain
      .getHeightedBlocks(TimeStamp.zero, TimeStamp.unsafe(Long.MaxValue))

    val ts      = all.rightValue._2.map { case (header, _) => header.timestamp }
    val heights = all.rightValue._2.map { case (_, heights) => heights }

    heights is AVector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

    def subset(from: TimeStamp, to: TimeStamp): AVector[Int] = {
      chain
        .getHeightedBlocks(from, to)
        .rightValue
        ._2
        .map { case (_, heights) => heights }
    }

    subset(ts(0), ts(9)) is heights
    subset(ts(2), ts(8)) is AVector(2, 3, 4, 5, 6, 7, 8)
    subset(ts(0), ts(0)) is AVector(0)
    subset(ts(9), ts(9)) is AVector(9)
    (0 to 8).foreach { i =>
      subset(ts(i), ts(i + 1)) is AVector(i, i + 1)
    }
    (0 to 8).foreach { i =>
      subset(ts(i + 1), ts(i)) is AVector.empty[Int]
    }
    subset(ts(6), ts(9).minusUnsafe(Duration.ofMillisUnsafe(1))) is AVector(6, 7, 8)
    val ten = ts(9).plusMillisUnsafe(1)
    subset(ten, ten) is AVector.empty[Int]
  }

  it should "fix hash indexing" in new Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val shortChain = chainGenOf(2, genesis).sample.get
    val longChain  = chainGenOf(4, genesis).sample.get
    val chain      = buildBlockChain()
    addBlocks(chain, shortChain)
    addBlocks(chain, longChain)

    val shortHash = shortChain(1).hash
    val longHash  = longChain(1).hash
    chain.getHashes(2) isE AVector(longHash, shortHash)
    chain.hashesCache.get(2).value is AVector(longHash, shortHash)
    chain.heightIndexStorage.put(2, AVector(shortHash, longHash))
    chain.hashesCache.put(2, AVector(shortHash, longHash))
    chain.getHashes(2) isE AVector(shortHash, longHash)

    chain.checkAndRepairHashIndexingUnsafe(3)
    chain.getHashes(2) isE AVector(longHash, shortHash)
    chain.hashesCache.get(2).value is AVector(longHash, shortHash)
  }

  trait RhoneFixture extends Fixture {
    lazy val chainIndex = genesis.chainIndex

    def createBlockChain(length: Int): BlockChain = {
      val chain  = buildBlockChain()
      val blocks = chainGenOf(chainIndex, length, genesis.hash, TimeStamp.now()).sample.get
      addBlocks(chain, blocks)
      chain
    }

    def createBlockChainWithUnusedGhostUncles(length: Int): (BlockChain, Set[BlockHeader]) = {
      val chain = buildBlockChain()
      // generate `length + 1` blocks to make sure this is the canonical chain
      val blocks    = chainGenOf(chainIndex, length + 1, genesis.hash, TimeStamp.now()).sample.get
      val allUncles = ArrayBuffer.empty[BlockHeader]
      addBlocks(chain, blocks)
      blocks.init.foreach { block =>
        val uncleGen = blockGen(block.chainIndex, block.timestamp, block.parentHash)
        val uncles   = AVector.fill(ALPH.MaxGhostUncleSize)(uncleGen.sample.get)
        allUncles ++= uncles.map(_.header)
        addBlocks(chain, uncles)
      }
      (chain, Set.from(allUncles))
    }

    def createBlockChainWithUsedGhostUncles(length: Int): (BlockChain, Set[BlockHeader]) = {
      val chain              = buildBlockChain()
      val allUncles          = ArrayBuffer.empty[BlockHeader]
      val allMainchainBlocks = ArrayBuffer.empty[BlockHash]
      // generate `length + 1` blocks to make sure this is the canonical chain
      (0 until length + 1).foreach { k =>
        val parentHash = allMainchainBlocks.lastOption.getOrElse(genesis.hash)
        val ghostUncleHashes = if (allUncles.size >= ALPH.MaxGhostUncleSize) {
          AVector.from(allUncles.takeRight(ALPH.MaxGhostUncleSize).map(_.hash))
        } else {
          AVector.empty
        }
        val selectedUncles =
          ghostUncleHashes.map(hash =>
            SelectedGhostUncle(hash, chain.getBlockUnsafe(hash).minerLockupScript, 1)
          )
        val block    = blockGen(chainIndex, TimeStamp.now(), parentHash, selectedUncles).sample.get
        val uncleGen = blockGen(block.chainIndex, block.timestamp, block.parentHash)
        val uncleSize =
          if (k == length) 0 else ALPH.MaxGhostUncleSize // no uncles for the latest block
        val newUncles = AVector.fill(uncleSize)(uncleGen.sample.get)
        allUncles ++= newUncles.map(_.header)
        addBlocks(chain, block +: newUncles)
        allMainchainBlocks += block.hash
      }
      allMainchainBlocks.size is length + 1
      allUncles.size is 2 * length
      (chain, Set.from(allUncles))
    }

    def createBlockWithInvalidGhostUncles(length: Int): BlockChain = {
      val chain = buildBlockChain()
      val now   = TimeStamp.now()
      // generate `length + 1` blocks to make sure this is the canonical chain
      val blocks     = chainGenOf(chainIndex, length + 1, genesis.hash, now).sample.get
      val forkChain0 = chainGenOf(chainIndex, length, genesis.hash, now).sample.get
      val forkChain1 = chainGenOf(chainIndex, length, genesis.hash, now).sample.get
      addBlocks(chain, blocks)
      addBlocks(chain, forkChain0)
      addBlocks(chain, forkChain1)
      chain
    }
  }

  it should "get the right used uncles when no uncles are used" in new RhoneFixture {
    val (chain, _) = createBlockChainWithUnusedGhostUncles(1)
    val bestTip    = chain.getBestTipUnsafe()
    val bestHeader = chain.getBlockHeaderUnsafe(bestTip)
    chain.getHeightUnsafe(bestTip) is 2
    chain.getHashes(1).rightValue.length is 3

    val (usedGhostUncleHashes, ancestors) =
      chain.getUsedGhostUnclesAndAncestors(bestHeader).rightValue
    usedGhostUncleHashes.isEmpty is true
    ancestors.map(chain.getHeightUnsafe) is AVector(1, 0)
    ancestors.contains(bestHeader.hash) is false
  }

  it should "get the right used uncles all uncles are used" in new RhoneFixture {
    val (chain, allUncles) = createBlockChainWithUsedGhostUncles(1)
    val bestTip            = chain.getBestTipUnsafe()
    val bestHeader         = chain.getBlockHeaderUnsafe(bestTip)
    chain.getHeightUnsafe(bestTip) is 2
    chain.getHashes(1).rightValue.length is 3

    val (usedGhostUncleHashes, ancestors) =
      chain.getUsedGhostUnclesAndAncestors(bestHeader).rightValue
    usedGhostUncleHashes.toSet is allUncles.map(_.hash)
    ancestors.map(chain.getHeightUnsafe) is AVector(1, 0)
    ancestors.contains(bestHeader.hash) is false
  }

  it should "select recent available uncles" in new RhoneFixture {
    private def test(chainLength: Int) = {
      val (chain, _) = createBlockChainWithUnusedGhostUncles(chainLength)
      (1 to chainLength).reverse.foreach(height => {
        val currentBlock = chain.getMainChainBlockByHeight(height).rightValue.get
        val (usedGhostUncleHashes, ancestors) =
          chain.getUsedGhostUnclesAndAncestors(currentBlock.header).rightValue
        usedGhostUncleHashes.isEmpty is true
        val fromHeight = if (height > ALPH.MaxGhostUncleAge) height - ALPH.MaxGhostUncleAge else 0
        ancestors is AVector.from(
          (fromHeight until height).view
            .map(chain.getMainChainBlockByHeight(_).rightValue.get.hash)
            .reverse
        )
        ancestors.length is math.min(height, ALPH.MaxGhostUncleAge)

        chain.selectGhostUncles(currentBlock.header, _ => false).rightValue.isEmpty is true
        val selectedUncles = chain.selectGhostUncles(currentBlock.header, _ => true).rightValue
        selectedUncles.length is ALPH.MaxGhostUncleSize
        selectedUncles.foreach { uncle =>
          uncle.lockupScript is chain
            .getBlockUnsafe(uncle.blockHash)
            .coinbase
            .unsigned
            .fixedOutputs(0)
            .lockupScript
          chain.getHeightUnsafe(uncle.blockHash) is height
          uncle.heightDiff is 1
        }
      })
    }

    test(ALPH.MaxGhostUncleAge - 2)
    test(ALPH.MaxGhostUncleAge)
    test(ALPH.MaxGhostUncleAge + 2)
  }

  it should "select uncles from unused uncles set" in new RhoneFixture {
    val (chain, _)    = createBlockChainWithUnusedGhostUncles(ALPH.MaxGhostUncleAge)
    val currentHeight = ALPH.MaxGhostUncleAge + 1
    var currentBlock  = chain.getMainChainBlockByHeight(currentHeight).rightValue.get
    chain.getHashes(currentHeight).rightValue.length is 1
    (1 to ALPH.MaxGhostUncleAge).foreach(index => {
      chain.selectGhostUncles(currentBlock.header, _ => false).rightValue.isEmpty is true
      val uncles = chain.selectGhostUncles(currentBlock.header, _ => true).rightValue
      val block =
        blockGen(
          currentBlock.chainIndex,
          currentBlock.timestamp,
          currentBlock.hash,
          uncles
        ).sample.get
      addBlock(chain, block)
      chain.getHeight(block.hash).isE(currentHeight + index)
      if (index >= ALPH.MaxGhostUncleAge + 1 - index) {
        uncles.isEmpty is true
      } else {
        uncles.map { uncle => chain.getHeightUnsafe(uncle.blockHash) } is AVector.fill(2)(
          ALPH.MaxGhostUncleAge + 1 - index
        )
      }

      val (usedUncles, ancestors) = chain.getUsedGhostUnclesAndAncestors(block.header).rightValue
      uncles.foreach(uncle => usedUncles.exists(_ == uncle.blockHash) is true)
      ancestors.length is ALPH.MaxGhostUncleAge
      currentBlock = block
    })

    (1 to ALPH.MaxGhostUncleSize).foreach(_ => {
      chain.selectGhostUncles(currentBlock.header, _ => true).rightValue.isEmpty is true
      val block =
        blockGen(currentBlock.chainIndex, currentBlock.timestamp, currentBlock.hash).sample.get
      addBlock(chain, block)
      currentBlock = block
    })
  }

  it should "select empty uncles if uncles is invalid" in new RhoneFixture {
    val chain      = createBlockWithInvalidGhostUncles(ALPH.MaxGhostUncleAge)
    val fromHeight = ALPH.MaxGhostUncleAge + 1
    (fromHeight to ALPH.MaxGhostUncleAge * 2).foreach(height => {
      val header = chain.getMainChainBlockByHeight(height).rightValue.get.header
      chain.selectGhostUncles(header, _ => true).rightValue.isEmpty is true
      val block = blockGen(header.chainIndex, header.timestamp, header.hash).sample.get
      addBlock(chain, block)
      chain.getHeight(block.hash).isE(height + 1)
    })
  }

  it should "select uncles and calc the block diff" in new RhoneFixture {
    val length = ALPH.MaxGhostUncleAge + 1
    val chain  = createBlockChain(length)
    chain.maxHeightByWeightUnsafe is length
    val parentHeight = Random.between(1, length - 2)
    val parentHash   = chain.getHashesUnsafe(parentHeight).head
    val uncle        = blockGen(chainIndex, TimeStamp.now(), parentHash).sample.get
    addBlock(chain, uncle)
    val bestTip = chain.getBestTipUnsafe()
    val selectedUncles =
      chain.selectGhostUnclesUnsafe(chain.getBlockHeaderUnsafe(bestTip), _ => true)
    selectedUncles is AVector(
      SelectedGhostUncle(uncle.hash, uncle.minerLockupScript, length - parentHeight)
    )
  }

  it should "get sync data based on max height" in new Fixture {
    val chain  = buildBlockChain()
    val blocks = addChain(chain, 4)
    addMaxWeightBlock(chain)

    blocks.foreachWithIndex { case (block, index) =>
      chain.getSyncDataUnsafe(AVector(block.hash)) is blocks.drop(index + 1).map(_.hash)
    }
  }

  it should "get latest hashes based on max height" in new Fixture {
    val chain          = buildBlockChain()
    val blocks0        = addChain(chain, 25)
    val maxWeightBlock = addMaxWeightBlock(chain)
    val result0        = blocks0.drop(4).map(_.hash)
    chain.getLatestHashesUnsafe() is result0

    val forkChainLength = 5
    val parentHash      = blocks0(3).hash
    val blocks1 =
      chainGenOf(chain.chainIndex, forkChainLength, parentHash, TimeStamp.now()).sample.get
    addBlocks(chain, blocks1)
    chain.getAllTips.toSet is Set(blocks0.last.hash, maxWeightBlock.hash, blocks1.last.hash)
    val result1 = AVector.from(0 until forkChainLength).flatMap { index =>
      AVector(result0(index), blocks1(index).hash)
    } ++ result0.drop(forkChainLength)
    chain.getLatestHashesUnsafe() is result1
  }

  it should "test getSyncDataFromHeightUnsafe" in new Fixture {
    val chain      = buildBlockChain()
    val chainIndex = chain.chainIndex
    val blocks0    = chainGenOf(chainIndex, 10, genesis.hash, TimeStamp.now()).sample.get
    val blocks1    = chainGenOf(chainIndex, 5, genesis.hash, TimeStamp.now()).sample.get
    addBlocks(chain, blocks0)
    addBlocks(chain, blocks1)
    chain.maxHeightUnsafe is 10

    (1 to 5).foreach { height =>
      val hashes0 = AVector.from(height to 5).flatMap { height =>
        AVector(blocks0(height - 1).hash, blocks1(height - 1).hash)
      }
      chain.getSyncDataFromHeightUnsafe(height) is hashes0 ++ blocks0.drop(5).map(_.hash)
    }
    (6 to 10).foreach { height =>
      chain.getSyncDataFromHeightUnsafe(height) is blocks0.drop(height - 1).map(_.hash)
    }

    val blocks2 = chainGenOf(chainIndex, maxSyncBlocksPerChain, blocks0.last).sample.get
    addBlocks(chain, blocks2)
    chain.maxHeightUnsafe is 10 + maxSyncBlocksPerChain
    val fork0 = blocks0 ++ blocks2
    val fork1 = blocks1
    (1 to 5).foreach { from =>
      val hashes0 = AVector.from(from to 5).flatMap { height =>
        AVector(fork0(height - 1).hash, fork1(height - 1).hash)
      }
      val to = from + maxSyncBlocksPerChain
      chain.getSyncDataFromHeightUnsafe(from) is hashes0 ++ fork0.slice(5, to).map(_.hash)
    }
    (6 to 9).foreach { from =>
      val to = from + maxSyncBlocksPerChain
      chain.getSyncDataFromHeightUnsafe(from) is fork0.slice(from - 1, to).map(_.hash)
    }
    val maxHeight = chain.maxHeightUnsafe
    (10 to maxHeight).foreach { from =>
      chain.getSyncDataFromHeightUnsafe(from) is fork0.slice(from - 1, maxHeight).map(_.hash)
    }
  }
}
