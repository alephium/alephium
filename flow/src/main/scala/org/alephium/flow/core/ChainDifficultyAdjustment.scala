package org.alephium.flow.core

import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.IOResult
import org.alephium.protocol.{ALF, Hash}
import org.alephium.util.{AVector, TimeStamp}

trait ChainDifficultyAdjustment {
  import ChainDifficultyAdjustment._

  implicit def consensusConfig: ConsensusSetting

  def getHeight(hash: Hash): IOResult[Int]

  def getTimestamp(hash: Hash): IOResult[TimeStamp]

  def chainBack(hash: Hash, heightUntil: Int): IOResult[AVector[Hash]]

  final protected def calMedianBlockTime(hash: Hash,
                                         height: Int): IOResult[(TimeStamp, TimeStamp)] = {
    val earlyHeight = height - consensusConfig.medianTimeInterval - 1
    assume(earlyHeight >= ALF.GenesisHeight)
    for {
      hashes     <- chainBack(hash, earlyHeight)
      timestamps <- hashes.mapE(h => getTimestamp(h))
    } yield {
      assume(timestamps.length == consensusConfig.medianTimeInterval + 1)
      calMedian(timestamps)
    }
  }

  // Digi Shield DAA
  final protected def calHashTarget(hash: Hash, currentTarget: BigInt): IOResult[BigInt] = {
    getHeight(hash).flatMap {
      case height if height > ALF.GenesisHeight + consensusConfig.medianTimeInterval =>
        calMedianBlockTime(hash, height).map {
          case (_median1, _median2) =>
            val median1 = _median1.millis
            val median2 = _median2.millis
            assume(median1 >= median2)
            var timeSpan = consensusConfig.expectedTimeSpan.millis + (median1 - median2 - consensusConfig.expectedTimeSpan.millis) / 4
            if (timeSpan < consensusConfig.timeSpanMin.millis) {
              timeSpan = consensusConfig.timeSpanMin.millis
            } else if (timeSpan > consensusConfig.timeSpanMax.millis) {
              timeSpan = consensusConfig.timeSpanMax.millis
            }
            reTarget(currentTarget, timeSpan)
        }
      case _ => Right(currentTarget)
    }
  }

  final protected def reTarget(currentTarget: BigInt, timeSpanMs: Long): BigInt = {
    currentTarget * timeSpanMs / consensusConfig.expectedTimeSpan.millis
  }
}

object ChainDifficultyAdjustment {
  def calMedian(timestamps: AVector[TimeStamp]): (TimeStamp, TimeStamp) = {
    val index   = (timestamps.length - 1) / 2
    val median1 = timestamps.tail.sorted.apply(index)
    val median2 = timestamps.init.sorted.apply(index)
    (median1, median2)
  }
}
