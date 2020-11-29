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

import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.IOResult
import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.model.Target
import org.alephium.util.{AVector, TimeStamp}

trait ChainDifficultyAdjustment {
  import ChainDifficultyAdjustment._

  implicit def consensusConfig: ConsensusSetting

  def getHeight(hash: Hash): IOResult[Int]

  def getTimestamp(hash: Hash): IOResult[TimeStamp]

  def chainBack(hash: Hash, heightUntil: Int): IOResult[AVector[Hash]]

  final protected[core] def calMedianBlockTime(hash: Hash,
                                               height: Int): IOResult[(TimeStamp, TimeStamp)] = {
    val earlyHeight = height - consensusConfig.medianTimeInterval - consensusConfig.powAveragingWindow
    assume(earlyHeight >= ALF.GenesisHeight)
    for {
      hashes     <- chainBack(hash, earlyHeight)
      timestamps <- hashes.mapE(h => getTimestamp(h))
    } yield {
      assume(
        timestamps.length == consensusConfig.medianTimeInterval + consensusConfig.powAveragingWindow)
      calMedian(timestamps, consensusConfig.medianTimeInterval)
    }
  }

  // Digi Shield DAA
  final protected[core] def calHashTarget(hash: Hash, currentTarget: Target): IOResult[Target] = {
    getHeight(hash).flatMap {
      case height
          if height >= ALF.GenesisHeight + consensusConfig.medianTimeInterval + consensusConfig.powAveragingWindow =>
        calMedianBlockTime(hash, height).map {
          case (_median1, _median2) =>
            val median1 = _median1.millis
            val median2 = _median2.millis
            assume(median1 >= median2)
            var timeSpan = consensusConfig.expectedWindowTimeSpan.millis + (median1 - median2 - consensusConfig.expectedWindowTimeSpan.millis) / 4
            if (timeSpan < consensusConfig.windowTimeSpanMin.millis) {
              timeSpan = consensusConfig.windowTimeSpanMin.millis
            } else if (timeSpan > consensusConfig.windowTimeSpanMax.millis) {
              timeSpan = consensusConfig.windowTimeSpanMax.millis
            }
            reTarget(currentTarget, timeSpan)
        }
      case _ => Right(currentTarget)
    }
  }

  final protected def reTarget(currentTarget: Target, timeSpanMs: Long): Target = {
    val nextTarget = currentTarget.value
      .multiply(BigInteger.valueOf(timeSpanMs))
      .divide(BigInteger.valueOf(consensusConfig.expectedWindowTimeSpan.millis))
    if (nextTarget.compareTo(consensusConfig.maxMiningTarget.value) <= 0) {
      Target.unsafe(nextTarget)
    } else {
      consensusConfig.maxMiningTarget
    }
  }
}

object ChainDifficultyAdjustment {
  def calMedian(timestamps: AVector[TimeStamp], interval: Int): (TimeStamp, TimeStamp) = {
    val index   = interval / 2
    val median1 = timestamps.takeRight(interval).sorted.apply(index)
    val median2 = timestamps.take(interval).sorted.apply(index)
    (median1, median2)
  }
}
