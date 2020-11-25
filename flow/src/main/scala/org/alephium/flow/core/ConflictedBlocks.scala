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
import org.alephium.protocol.Hash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Block, ChainIndex, TxOutputRef}
import org.alephium.util.{AVector, Duration, TimeStamp}

trait ConflictedBlocks {
  implicit def brokerConfig: BrokerConfig
  def consensusConfig: ConsensusSetting

  lazy val caches = AVector.fill(brokerConfig.groupNumPerBroker)(
    ConflictedBlocks.emptyCache(consensusConfig.conflictCacheKeepDuration))

  def getCache(block: Block): GroupCache =
    caches(block.chainIndex.from.value - brokerConfig.groupFrom)

  def getCache(hash: Hash): GroupCache =
    caches(ChainIndex.from(hash).from.value - brokerConfig.groupFrom)

  def cacheForConflicts(block: Block): Unit = getCache(block).add(block)

  def isConflicted(hashes: AVector[Hash], getBlock: Hash => Block): Boolean = {
    getCache(hashes.head).isConflicted(hashes, getBlock)
  }
}

object ConflictedBlocks {
  final case class GroupCache(keepDuration: Duration,
                              blockCache: mutable.HashMap[Hash, Block],
                              txCache: mutable.HashMap[TxOutputRef, ArrayBuffer[Hash]],
                              conflictedBlocks: mutable.HashMap[Hash, ArrayBuffer[Hash]]) {
    def isBlockCached(block: Block): Boolean = isBlockCached(block.hash)
    def isBlockCached(hash: Hash): Boolean   = blockCache.contains(hash)

    def add(block: Block): Unit = if (!isBlockCached(block)) {
      blockCache.addOne(block.hash -> block)

      val conflicts = mutable.HashSet.empty[Hash]
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

      uncacheOldTips(keepDuration)
    }

    def remove(block: Block): Unit = if (isBlockCached(block)) {
      blockCache.remove(block.hash)

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

    private def isConflicted(hash: Hash, newDeps: AVector[Hash]): Boolean = {
      conflictedBlocks.get(hash).exists { conflicts =>
        val depsSet = newDeps.toSet
        conflicts.exists(hash => depsSet.contains(hash))
      }
    }

    def isConflicted(hashes: AVector[Hash], getBlock: Hash => Block): Boolean = {
      hashes.foreach(hash => if (!isBlockCached(hash)) add(getBlock(hash)))

      hashes.existsWithIndex {
        case (hash, index) =>
          isConflicted(hash, hashes.drop(index + 1))
      }
    }

    def uncacheOldTips(duration: Duration): Unit = {
      val earliestTs = TimeStamp.now().minusUnsafe(duration)
      val toRemove   = blockCache.values.filter(_.timestamp < earliestTs)
      toRemove.foreach(block => blockCache.remove(block.hash))
    }
  }

  def emptyCache(keepDuration: Duration): GroupCache =
    GroupCache(keepDuration, mutable.HashMap.empty, mutable.HashMap.empty, mutable.HashMap.empty)
}
