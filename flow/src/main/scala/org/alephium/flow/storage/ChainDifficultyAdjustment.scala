package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.util.ConcurrentHashMap

trait ChainDifficultyAdjustment extends BlockHashPool {
  implicit def config: PlatformConfig

  protected def blockHashesTable: ConcurrentHashMap[Keccak256, BlockHashChain.TreeNode]

  protected def calMedianBlockTime(node: BlockHashChain.TreeNode): Option[Long] = {
    if (node.height < config.medianTimeInterval) None
    else {
      var cur = node
      val timestamps = Array.fill(config.medianTimeInterval) {
        val timestamp = cur.timestamp
        cur = cur.parentOpt.get
        timestamp
      }
      Some(calMedian(timestamps))
    }
  }

  protected def calMedian(timestamps: Array[Long]): Long = {
    scala.util.Sorting.quickSort(timestamps)
    timestamps(timestamps.length / 2)
  }

  // Digi Shield DAA
  protected def calHashTarget(hash: Keccak256, currentTarget: BigInt): BigInt = {
    assert(contains(hash))
    val node = blockHashesTable(hash)
    val targetOpt = for {
      median1 <- calMedianBlockTime(node)
      parent  <- node.parentOpt
      median2 <- calMedianBlockTime(parent)
    } yield {
      var timeSpan = median1 - median2
      if (timeSpan < config.timeSpanMin) {
        timeSpan = config.timeSpanMin
      } else if (timeSpan > config.timeSpanMax) {
        timeSpan = config.timeSpanMax
      }
      currentTarget * timeSpan / config.expectedTimeSpan
    }

    targetOpt.fold(currentTarget)(identity)
  }

  protected def reTarget(currentTarget: BigInt, timeSpan: Long): BigInt = {
    currentTarget * timeSpan / config.expectedTimeSpan
  }
}
