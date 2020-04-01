package org.alephium.flow.core

import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash
import org.alephium.util.{ConcurrentHashMap, TimeStamp}

trait ChainDifficultyAdjustment extends BlockHashPool {
  implicit def config: PlatformConfig

  protected def blockHashesTable: ConcurrentHashMap[Hash, BlockHashChain.TreeNode]

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  protected def calMedianBlockTime(node: BlockHashChain.TreeNode): Option[TimeStamp] = {
    if (node.height < config.medianTimeInterval) None
    else {
      var cur = node
      val timestamps = Array.fill(config.medianTimeInterval) {
        val timestamp = cur.timestamp
        cur = cur.parentOpt.get // cur.parentOpt is always nonEmpty
        timestamp
      }
      Some(calMedian(timestamps))
    }
  }

  protected def calMedian(timestamps: Array[TimeStamp]): TimeStamp = {
    scala.util.Sorting.quickSort(timestamps)
    timestamps(timestamps.length / 2)
  }

  // Digi Shield DAA
  protected def calHashTarget(hash: Hash, currentTarget: BigInt): BigInt = {
    assert(contains(hash))
    val node = blockHashesTable(hash)
    val targetOpt = for {
      median1 <- calMedianBlockTime(node).map(_.millis)
      parent  <- node.parentOpt
      median2 <- calMedianBlockTime(parent).map(_.millis)
    } yield {
      assume(median1 >= median2)
      var timeSpan = config.expectedTimeSpan.millis + (median1 - median2 - config.expectedTimeSpan.millis) / 4
      if (timeSpan < config.timeSpanMin.millis) {
        timeSpan = config.timeSpanMin.millis
      } else if (timeSpan > config.timeSpanMax.millis) {
        timeSpan = config.timeSpanMax.millis
      }
      reTarget(currentTarget, timeSpan)
    }

    targetOpt.fold(currentTarget)(identity)
  }

  protected def reTarget(currentTarget: BigInt, timeSpanMs: Long): BigInt = {
    currentTarget * timeSpanMs / config.expectedTimeSpan.millis
  }
}
