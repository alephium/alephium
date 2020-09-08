package org.alephium.flow.core

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.alephium.flow.core.ConflictedBlocks.GroupCache
import org.alephium.protocol.Hash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Block, ChainIndex, TxOutputRef}
import org.alephium.util.AVector

trait ConflictedBlocks {
  implicit def brokerConfig: BrokerConfig

  lazy val caches = AVector.fill(brokerConfig.groupNumPerBroker)(ConflictedBlocks.emptyCache)

  def getCache(block: Block): GroupCache =
    caches(block.chainIndex.from.value - brokerConfig.groupFrom)

  def getCache(hash: Hash): GroupCache =
    caches(ChainIndex.from(hash).from.value - brokerConfig.groupFrom)

  def cacheForConflicts(block: Block): Unit = getCache(block).add(block)

  def isConflicted(hash: Hash, newDeps: AVector[Hash], getBlock: Hash => Block): Boolean = {
    getCache(hash).isConflicted(hash, newDeps, getBlock)
  }
}

object ConflictedBlocks {
  final case class GroupCache(blockCache: mutable.HashMap[Hash, Block],
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
        conflicts.foreach { hash =>
          conflictedBlocks.get(hash) match {
            case Some(conflicts) => conflicts.addOne(block.hash)
            case None            => conflictedBlocks += hash -> ArrayBuffer(block.hash)
          }
        }
      }
    }

    def remove(block: Block): Unit = if (isBlockCached(block)) {
      blockCache.remove(block.hash)

      block.transactions.foreach { tx =>
        tx.unsigned.inputs.foreach { input =>
          txCache.get(input.outputRef) match {
            case Some(blockHashes) =>
              blockHashes.subtractOne(block.hash)
              if (blockHashes.isEmpty) txCache.subtractOne(input.outputRef)
            case None => ()
          }
        }
      }

      conflictedBlocks.get(block.hash) match {
        case Some(conflicts) =>
          conflicts.foreach { hash =>
            val conflicts = conflictedBlocks(hash)
            conflicts.subtractOne(block.hash)
            if (conflicts.isEmpty) conflictedBlocks.subtractOne(hash)
          }
          conflictedBlocks.subtractOne(block.hash)
        case None => ()
      }
    }

    def isConflicted(hash: Hash, newDeps: AVector[Hash], getBlock: Hash => Block): Boolean = {
      newDeps.foreach(hash => if (!isBlockCached(hash)) add(getBlock(hash)))
      conflictedBlocks.get(hash).exists { conflicts =>
        val depsSet = newDeps.toSet
        conflicts.exists(hash => depsSet.contains(hash))
      }
    }
  }

  def emptyCache: GroupCache =
    GroupCache(mutable.HashMap.empty, mutable.HashMap.empty, mutable.HashMap.empty)
}
