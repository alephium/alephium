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

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.alephium.flow.core.ConflictedBlocks.GroupCache
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Duration, TimeStamp}

trait ConflictedBlocks {
  implicit def brokerConfig: BrokerConfig
  def consensusConfig: ConsensusSetting

  def getHashesForDoubleSpendingCheckUnsafe(
      groupIndex: GroupIndex,
      deps: BlockDeps
  ): AVector[BlockHash]

  lazy val caches = AVector.fill(brokerConfig.groupNumPerBroker)(
    ConflictedBlocks.emptyCache(
      consensusConfig.blockCacheCapacityPerChain * brokerConfig.groups,
      consensusConfig.conflictCacheKeepDuration
    )
  )

  @inline def getCache(block: Block): GroupCache =
    getCache(block.hash)

  @inline def getCache(hash: BlockHash): GroupCache =
    getCache(ChainIndex.from(hash).from)

  @inline def getCache(target: GroupIndex): GroupCache =
    caches(brokerConfig.groupIndexOfBroker(target))

  def cacheForConflicts(block: Block): Unit = getCache(block).add(block)

  def isConflicted(hashes: AVector[BlockHash], getBlock: BlockHash => Block): Boolean = {
    getCache(hashes.head).isConflicted(hashes, getBlock)
  }

  def filterConflicts(
      target: GroupIndex,
      deps: BlockDeps,
      txs: AVector[TransactionTemplate],
      getBlock: BlockHash => Block
  ): AVector[TransactionTemplate] = {
    val diffs = getHashesForDoubleSpendingCheckUnsafe(target, deps)
    getCache(target).filterConflicts(diffs, txs, getBlock)
  }

  def isTxConflicted(
      targetGroup: GroupIndex,
      tx: TransactionTemplate
  ): Boolean = {
    getCache(targetGroup).isTxConflicted(tx)
  }
}

object ConflictedBlocks {
  final case class GroupCache(
      cacheSizeBoundary: Int,
      keepDuration: Duration,
      blockCache: mutable.HashMap[BlockHash, Block],
      txCache: mutable.HashMap[TxOutputRef, ArrayBuffer[BlockHash]],
      conflictedBlocks: mutable.HashMap[BlockHash, ArrayBuffer[BlockHash]]
  ) {
    def isBlockCached(block: Block): Boolean    = isBlockCached(block.hash)
    def isBlockCached(hash: BlockHash): Boolean = blockCache.contains(hash)

    def add(block: Block): Unit = this.synchronized(addUnsafe(block))

    // thread unsafe
    def addUnsafe(block: Block): Unit =
      if (!isBlockCached(block)) {
        blockCache.addOne(block.hash -> block)

        val conflicts = mutable.HashSet.empty[BlockHash]
        block.transactions.foreach { tx =>
          tx.unsigned.inputs.foreach { input =>
            txCache.get(input.outputRef) match {
              case Some(blockHashes) =>
                if (!blockHashes.contains(block.hash)) {
                  blockHashes.foreach(conflicts.add)
                  blockHashes.addOne(block.hash)
                }
              case None =>
                txCache += input.outputRef -> ArrayBuffer(block.hash)
            }
          }
        }

        if (conflicts.nonEmpty) {
          conflictedBlocks += block.hash -> ArrayBuffer.from(conflicts)
          conflicts.foreach[Unit] { hash =>
            conflictedBlocks.get(hash) match {
              case Some(conflicts) => conflicts.addOne(block.hash)
              case None            => conflictedBlocks += hash -> ArrayBuffer(block.hash)
            }
          }
        }
      }

    // thread unsafe
    def remove(block: Block): Unit =
      blockCache.remove(block.hash).foreach { block =>
        block.transactions.foreach { tx =>
          tx.unsigned.inputs.foreach { input =>
            txCache.get(input.outputRef).foreach { blockHashes =>
              blockHashes.subtractOne(block.hash)
              if (blockHashes.isEmpty) txCache.subtractOne(input.outputRef)
            }
          }
        }

        conflictedBlocks.get(block.hash).foreach { conflicts =>
          conflicts.foreach { hash =>
            val conflicts = conflictedBlocks(hash)
            conflicts.subtractOne(block.hash)
            if (conflicts.isEmpty) conflictedBlocks.subtractOne(hash)
          }
          conflictedBlocks.subtractOne(block.hash)
        }
      }

    private def isConflicted(hash: BlockHash, newDeps: AVector[BlockHash]): Boolean = {
      conflictedBlocks.get(hash).exists { conflicts =>
        val depsSet = newDeps.toSet
        conflicts.exists(hash => depsSet.contains(hash))
      }
    }

    def isConflicted(tx: TransactionAbstract, diffs: AVector[BlockHash]): Boolean = {
      tx.unsigned.inputs.exists(input =>
        txCache.get(input.outputRef).exists(_.exists(diffs.contains))
      )
    }

    def cacheAll(hashes: AVector[BlockHash], getBlock: BlockHash => Block): Unit = {
      hashes.foreach(hash => if (!isBlockCached(hash)) add(getBlock(hash)))
    }

    def isConflicted(hashes: AVector[BlockHash], getBlock: BlockHash => Block): Boolean =
      this.synchronized {
        cacheAll(hashes, getBlock)

        val result = hashes.existsWithIndex { case (hash, index) =>
          isConflicted(hash, hashes.drop(index + 1))
        }

        uncacheOldTips(keepDuration, hashes.head)
        result
      }

    def filterConflicts(
        diffs: AVector[BlockHash],
        txs: AVector[TransactionTemplate],
        getBlock: BlockHash => Block
    ): AVector[TransactionTemplate] =
      this.synchronized {
        cacheAll(diffs, getBlock)
        txs.filterNot(tx => isConflicted(tx, diffs))
      }

    def isTxConflicted(tx: TransactionTemplate): Boolean = {
      this.synchronized {
        tx.unsigned.inputs.exists(input => txCache.get(input.outputRef).exists(_.nonEmpty))
      }
    }

    private def uncacheOldTips(duration: Duration, hash: BlockHash): Unit = {
      if (blockCache.size >= cacheSizeBoundary) {
        val block = blockCache(hash)
        assume(!block.isGenesis)
        val earliestTs1 = TimeStamp.now().minusUnsafe(duration)
        val earliestTs2 = block.header.timestamp.minusUnsafe(duration)
        val earliestTs  = if (earliestTs1 < earliestTs2) earliestTs1 else earliestTs2
        val toRemove    = blockCache.values.filter(_.timestamp <= earliestTs)
        toRemove.foreach(block => remove(block))
      }
    }
  }

  def emptyCache(cacheSizeBoundary: Int, keepDuration: Duration): GroupCache =
    GroupCache(
      cacheSizeBoundary,
      keepDuration,
      mutable.HashMap.empty,
      mutable.HashMap.empty,
      mutable.HashMap.empty
    )
}
