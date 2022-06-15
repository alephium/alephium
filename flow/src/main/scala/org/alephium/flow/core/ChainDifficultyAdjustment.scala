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
import org.alephium.protocol.{ALPH, BlockHash}
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model.Target
import org.alephium.util.{AVector, Duration, TimeStamp}

trait ChainDifficultyAdjustment {
  implicit def consensusConfig: ConsensusSetting
  implicit def networkConfig: NetworkConfig

  def getHeight(hash: BlockHash): IOResult[Int]

  def getTimestamp(hash: BlockHash): IOResult[TimeStamp]

  def chainBackUntil(hash: BlockHash, heightUntil: Int): IOResult[AVector[BlockHash]]

  // TODO: optimize this
  final protected[core] def calTimeSpan(hash: BlockHash, height: Int): IOResult[Duration] = {
    val earlyHeight = height - consensusConfig.powAveragingWindow - 1
    assume(earlyHeight >= ALPH.GenesisHeight)
    for {
      hashes        <- chainBackUntil(hash, earlyHeight)
      timestampNow  <- getTimestamp(hash)
      timestampLast <- getTimestamp(hashes.head)
    } yield timestampNow.deltaUnsafe(timestampLast)
  }

  final def calIceAgeTarget(
      currentTarget: Target,
      timestamp: TimeStamp
  ): Target = {
    val hardFork = networkConfig.getHardFork(timestamp)
    if (hardFork.isLemanEnabled()) {
      calIceAgeTarget(currentTarget, timestamp, ALPH.LemanDifficultyBombEnabledTimestamp)
    } else {
      calIceAgeTarget(currentTarget, timestamp, ALPH.PreLemanDifficultyBombEnabledTimestamp)
    }
  }

  final def calIceAgeTarget(
      currentTarget: Target,
      timestamp: TimeStamp,
      difficultyBombEnabledTimestamp: TimeStamp
  ): Target = {
    if (timestamp.isBefore(difficultyBombEnabledTimestamp)) {
      currentTarget
    } else {
      val periodCount = timestamp
        .deltaUnsafe(difficultyBombEnabledTimestamp)
        .millis / ALPH.ExpDiffPeriod.millis
      Target.unsafe(currentTarget.value.shiftRight(periodCount.toInt))
    }
  }

  // DigiShield DAA V3 variant
  final protected[core] def calNextHashTargetRaw(
      hash: BlockHash,
      currentTarget: Target,
      timestamp: TimeStamp
  ): IOResult[Target] = {
    getHeight(hash).flatMap {
      case height if height >= ALPH.GenesisHeight + consensusConfig.powAveragingWindow + 1 =>
        calTimeSpan(hash, height).map { timeSpan =>
          var clippedTimeSpan =
            consensusConfig.expectedWindowTimeSpan.millis + (timeSpan.millis - consensusConfig.expectedWindowTimeSpan.millis) / 4
          if (clippedTimeSpan < consensusConfig.windowTimeSpanMin.millis) {
            clippedTimeSpan = consensusConfig.windowTimeSpanMin.millis
          } else if (clippedTimeSpan > consensusConfig.windowTimeSpanMax.millis) {
            clippedTimeSpan = consensusConfig.windowTimeSpanMax.millis
          }
          val target = reTarget(currentTarget, clippedTimeSpan)
          calIceAgeTarget(target, timestamp)
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
