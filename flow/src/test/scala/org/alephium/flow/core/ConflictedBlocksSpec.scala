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

import org.scalacheck.Gen

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.config.{GroupConfigFixture, NetworkConfigFixture}
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, Duration, TimeStamp}

class ConflictedBlocksSpec
    extends AlephiumSpec
    with TxInputGenerators
    with GroupConfigFixture
    with NetworkConfigFixture.Default {
  val groups   = 3
  val txInputs = Gen.listOfN(10, txInputGen).sample.get.toIndexedSeq

  trait Fixture {
    def blockGen0(txInputs: TxInput*): Block = {
      val transaction =
        Transaction.from(
          AVector.from(txInputs),
          AVector.empty[AssetOutput],
          AVector.empty[Signature]
        )
      Block.from(
        AVector.fill(groupConfig.depsNum)(BlockHash.zero),
        Hash.zero,
        AVector(transaction),
        Target.Max,
        TimeStamp.now(),
        Nonce.unsecureRandom()
      )
    }

    def blockGen1(txInputs: AVector[TxInput]*): Block = {
      val transactions = txInputs.map(inputs =>
        Transaction.from(inputs, AVector.empty[AssetOutput], AVector.empty[Signature])
      )
      Block.from(
        AVector.fill(groupConfig.depsNum)(BlockHash.zero),
        Hash.zero,
        AVector.from(transactions),
        Target.Max,
        TimeStamp.now(),
        Nonce.unsecureRandom()
      )
    }

    val cache = ConflictedBlocks.emptyCache(0, Duration.ofMinutesUnsafe(10))
  }

  it should "add block into cache" in new Fixture {
    val block = blockGen1(AVector(txInputs(0), txInputs(1)), AVector(txInputs(2), txInputs(3)))
    cache.add(block)
    cache.isBlockCached(block) is true
    cache.isBlockCached(block.hash) is true
    cache.txCache.size is 4
    cache.conflictedBlocks.size is 0

    cache.add(block) // add the block again
    cache.isBlockCached(block) is true
    cache.isBlockCached(block.hash) is true
    cache.txCache.size is 4
    cache.conflictedBlocks.size is 0
  }

  it should "remove caches" in new Fixture {
    val block = blockGen1(AVector(txInputs(0), txInputs(1)), AVector(txInputs(2), txInputs(3)))
    cache.add(block)
    cache.remove(block)

    cache.isBlockCached(block) is false
    cache.txCache.size is 0
    cache.conflictedBlocks.size is 0
  }

  trait Fixture1 extends Fixture {
    val block0 = blockGen0(txInputs(0), txInputs(1))
    val block1 = blockGen0(txInputs(1), txInputs(2))
    val block2 = blockGen0(txInputs(2), txInputs(3))
    val blocks = AVector(block0, block1, block2)
  }

  it should "detect conflicts" in new Fixture1 {
    blocks.foreach(cache.add)
    blocks.foreach(block => cache.isBlockCached(block) is true)
    cache.txCache.size is 4
    cache.txCache(txInputs(0).outputRef).toSet is Set(block0.hash)
    cache.txCache(txInputs(1).outputRef).toSet is Set(block0.hash, block1.hash)
    cache.txCache(txInputs(2).outputRef).toSet is Set(block1.hash, block2.hash)
    cache.txCache(txInputs(3).outputRef).toSet is Set(block2.hash)

    cache.conflictedBlocks.size is 3
    cache.conflictedBlocks(block0.hash).toSet is Set(block1.hash)
    cache.conflictedBlocks(block1.hash).toSet is Set(block0.hash, block2.hash)
    cache.conflictedBlocks(block2.hash).toSet is Set(block1.hash)

    cache.isConflicted(
      block0.transactions.head,
      AVector(block0.hash, block1.hash, block2.hash)
    ) is true
    cache.isConflicted(
      block1.transactions.head,
      AVector(block0.hash, block1.hash, block2.hash)
    ) is true
    cache.isConflicted(
      block2.transactions.head,
      AVector(block0.hash, block1.hash, block2.hash)
    ) is true
    cache.isConflicted(block0.transactions.head, AVector(block0.hash)) is true
    cache.isConflicted(block0.transactions.head, AVector(block1.hash)) is true
    cache.isConflicted(block0.transactions.head, AVector(block2.hash)) is false
    cache.isConflicted(block1.transactions.head, AVector(block0.hash)) is true
    cache.isConflicted(block1.transactions.head, AVector(block1.hash)) is true
    cache.isConflicted(block1.transactions.head, AVector(block2.hash)) is true
    cache.isConflicted(block2.transactions.head, AVector(block0.hash)) is false
    cache.isConflicted(block2.transactions.head, AVector(block1.hash)) is true
    cache.isConflicted(block2.transactions.head, AVector(block2.hash)) is true
  }

  it should "add, remove blocks properly 0" in new Fixture1 {
    blocks.foreach(cache.add)
    cache.remove(block0)
    cache.isBlockCached(block0) is false
    cache.txCache.size is 3
    cache.conflictedBlocks.size is 2
    cache.remove(block1)
    cache.isBlockCached(block1) is false
    cache.txCache.size is 2
    cache.conflictedBlocks.size is 0
    cache.remove(block2)
    blocks.foreach(block => cache.isBlockCached(block) is false)
    cache.txCache.size is 0
    cache.conflictedBlocks.size is 0
  }

  it should "add, remove blocks properly 1" in new Fixture1 {
    blocks.foreach(cache.add)
    cache.remove(block1)
    cache.isBlockCached(block1) is false
    cache.txCache.size is 4
    cache.conflictedBlocks.size is 0
    cache.remove(block0)
    cache.isBlockCached(block0) is false
    cache.txCache.size is 2
    cache.conflictedBlocks.size is 0
    cache.remove(block2)
    blocks.foreach(block => cache.isBlockCached(block) is false)
    cache.txCache.size is 0
    cache.conflictedBlocks.size is 0
  }

  it should "cache nothing when keep duration is 0 and cache size is small" in new Fixture1 {
    Thread.sleep(10) // make sure blocks are old now

    val cache0 = ConflictedBlocks.emptyCache(0, Duration.ofMinutesUnsafe(0))
    blocks.foreach(cache0.add)
    blocks.foreach(block => cache0.isBlockCached(block) is true)

    val latestBlock = blocks.maxBy(_.header.timestamp)
    cache0.isConflicted((latestBlock +: blocks).map(_.hash), _ => ???)
    blocks.foreach(cache0.isBlockCached(_) is false)
  }

  it should "cache everything when keep duration is 0 but cache size is big" in new Fixture1 {
    Thread.sleep(10) // make sure blocks are old now

    val cache0 = ConflictedBlocks.emptyCache(blocks.length + 1, Duration.ofMinutesUnsafe(0))
    blocks.foreach(cache0.add)
    blocks.foreach(block => cache0.isBlockCached(block) is true)

    val latestBlock = blocks.maxBy(_.header.timestamp)
    cache0.isConflicted((latestBlock +: blocks).map(_.hash), _ => ???)
    blocks.foreach(cache0.isBlockCached(_) is true)
  }

  trait Fixture2 extends Fixture {
    val block0 = blockGen0(txInputs(0))
    val block1 = blockGen0(txInputs(0))
    val blocks = Seq(block0, block1)
  }

  it should "detect conflicts 2" in new Fixture2 {
    blocks.foreach(cache.add)
    blocks.foreach(block => cache.isBlockCached(block) is true)
    cache.txCache.size is 1
    cache.txCache(txInputs(0).outputRef).toSet is Set(block0.hash, block1.hash)
    cache.conflictedBlocks.size is 2
    cache.conflictedBlocks(block0.hash).toSet is Set(block1.hash)
    cache.conflictedBlocks(block1.hash).toSet is Set(block0.hash)

    cache.isConflicted(
      AVector(block0.hash, block1.hash),
      hash => blocks.filter(_.hash equals hash).head
    ) is true
  }
}
