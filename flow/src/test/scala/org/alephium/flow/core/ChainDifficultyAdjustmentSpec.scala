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

import scala.collection.mutable
import scala.util.Random

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.IOResult
import org.alephium.protocol.{ALPH, BlockHash}
import org.alephium.protocol.config.{NetworkConfig, NetworkConfigFixture}
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model.Target
import org.alephium.util.{AVector, Duration, NumericHelpers, TimeStamp}

class ChainDifficultyAdjustmentSpec extends AlephiumFlowSpec { Test =>
  trait MockFixture extends ChainDifficultyAdjustment with NumericHelpers {
    implicit val consensusConfig: ConsensusSetting = {
      val blockTargetTime = Duration.ofSecondsUnsafe(64)
      val emission        = Emission(Test.groupConfig, blockTargetTime)
      ConsensusSetting(blockTargetTime, blockTargetTime, 18, 25, emission)
    }
    implicit override def networkConfig: NetworkConfig = NetworkConfigFixture.Leman

    val chainInfo =
      mutable.HashMap.empty[BlockHash, (Int, TimeStamp)] // block hash -> (height, timestamp)
    val threshold = consensusConfig.powAveragingWindow + 1

    def getHeight(hash: BlockHash): IOResult[Int] = Right(chainInfo(hash)._1)

    def getHash(height: Int): BlockHash = chainInfo.filter(_._2._1 equals height).head._1

    def getTimestamp(hash: BlockHash): IOResult[TimeStamp] = Right(chainInfo(hash)._2)

    def chainBackUntil(hash: BlockHash, heightUntil: Int): IOResult[AVector[BlockHash]] = {
      val maxHeight: Int = getHeight(hash).rightValue
      val hashes = AVector
        .from(chainInfo.filter { case (_, (height, _)) =>
          height > heightUntil && height <= maxHeight
        }.keys)
        .sortBy(getHeight(_).rightValue)
      Right(hashes)
    }

    def setup(data: AVector[(BlockHash, TimeStamp)]): Unit = {
      assume(chainInfo.isEmpty)
      data.foreachWithIndex { case ((hash, timestamp), height) =>
        chainInfo(hash) = height -> timestamp
      }
    }

    def calTimeSpan(hash: BlockHash): IOResult[Duration] = {
      calTimeSpan(hash, getHeight(hash).rightValue)
    }
  }

  it should "calculate target correctly" in new MockFixture {
    val currentTarget = Target.unsafe((consensusConfig.maxMiningTarget / 4).underlying())
    reTarget(currentTarget, consensusConfig.expectedWindowTimeSpan.millis) is currentTarget
    reTarget(currentTarget, (consensusConfig.expectedWindowTimeSpan timesUnsafe 2).millis).value is
      (currentTarget * 2).underlying()
    reTarget(currentTarget, (consensusConfig.expectedWindowTimeSpan divUnsafe 2).millis) is
      Target.unsafe((currentTarget.value / 2).underlying())
  }

  it should "calculate correct time span" in new MockFixture {
    val genesisTs = TimeStamp.now()
    val data = AVector.tabulate(threshold + 1) { height =>
      BlockHash.random -> (genesisTs + consensusConfig.expectedTimeSpan.timesUnsafe(height.toLong))
    }
    setup(data)

    val median1 = data(18)._2
    val median2 = data(1)._2
    calTimeSpan(data.last._1) isE (median1 deltaUnsafe median2)
  }

  it should "return initial target when few blocks" in {
    val maxHeight = ALPH.GenesisHeight + consensusConfig.powAveragingWindow + 1
    (1 until maxHeight).foreach { n =>
      val data       = AVector.fill(n)(BlockHash.random -> TimeStamp.zero)
      val fixture    = new MockFixture { setup(data) }
      val latestHash = data.last._1
      fixture.chainBackUntil(latestHash, 0) isE data.tail.map(_._1)
      val currentTarget = Target.unsafe(BigInteger.valueOf(Random.nextLong(Long.MaxValue)))
      fixture.calNextHashTargetRaw(
        latestHash,
        currentTarget,
        ALPH.LaunchTimestamp
      ) isE currentTarget
    }
  }

  it should "return the same target when block times are exact" in new MockFixture {
    val genesisTs = TimeStamp.now()
    val data = AVector.tabulate(2 * threshold) { height =>
      BlockHash.random -> (genesisTs + consensusConfig.expectedTimeSpan.timesUnsafe(height.toLong))
    }
    setup(data)

    (threshold until 2 * threshold).foreach { height =>
      val hash          = getHash(height)
      val currentTarget = Target.unsafe(BigInteger.valueOf(Random.nextLong(Long.MaxValue)))
      calTimeSpan(hash, height) isE (data(height)._2 deltaUnsafe data(height - 17)._2)
      calNextHashTargetRaw(hash, currentTarget, ALPH.LaunchTimestamp) isE currentTarget
    }
  }

  it should "decrease the target when blocktime keep increasing" in new MockFixture {
    var currentTs = TimeStamp.now()
    var ratio     = 1.0
    val data = AVector.tabulate(2 * threshold) { _ =>
      ratio = ratio * 1.2
      val delta = (consensusConfig.expectedTimeSpan.millis * ratio).toLong
      currentTs = currentTs.plusMillisUnsafe(delta)
      BlockHash.random -> currentTs
    }
    setup(data)

    (threshold until 2 * threshold).foreach { height =>
      val hash          = getHash(height)
      val currentTarget = Target.unsafe(BigInteger.valueOf(1024))
      calTimeSpan(hash, height) isE (data(height)._2 deltaUnsafe data(height - 17)._2)
      calNextHashTargetRaw(hash, currentTarget, ALPH.LaunchTimestamp) isE
        reTarget(currentTarget, consensusConfig.windowTimeSpanMax.millis)
    }
  }

  it should "increase the target when blocktime keep decreasing" in new MockFixture {
    var currentTs = TimeStamp.now()
    var ratio     = 1.0
    val data = AVector.tabulate(2 * threshold) { _ =>
      ratio = ratio * 1.2
      val delta = (consensusConfig.expectedTimeSpan.millis / ratio).toLong
      currentTs = currentTs.plusMillisUnsafe(delta)
      BlockHash.random -> currentTs
    }
    setup(data)

    (threshold until 2 * threshold).foreach { height =>
      val hash          = getHash(height)
      val currentTarget = Target.unsafe(BigInteger.valueOf(1024))
      calTimeSpan(hash, height) isE (data(height)._2 deltaUnsafe data(height - 17)._2)
      calNextHashTargetRaw(hash, currentTarget, ALPH.LaunchTimestamp) isE
        reTarget(currentTarget, consensusConfig.windowTimeSpanMin.millis)
    }
  }

  it should "decrease the target when difficulty bomb enabled" in new MockFixture {
    val currentTarget = consensusConfig.maxMiningTarget
    val timestamp0 =
      ALPH.LemanDifficultyBombEnabledTimestamp.minusUnsafe(Duration.ofSecondsUnsafe(1))
    calIceAgeTarget(currentTarget, timestamp0) is currentTarget

    calIceAgeTarget(
      currentTarget,
      ALPH.PreLemanDifficultyBombEnabledTimestamp.plusUnsafe(ALPH.ExpDiffPeriod)
    ) is currentTarget
    calIceAgeTarget(
      currentTarget,
      ALPH.LemanDifficultyBombEnabledTimestamp.plusUnsafe(ALPH.ExpDiffPeriod)
    ) isnot currentTarget

    (0 until 20).foreach { i =>
      val period    = ALPH.ExpDiffPeriod.timesUnsafe(i.toLong)
      val timestamp = ALPH.LemanDifficultyBombEnabledTimestamp.plusUnsafe(period)
      val target    = calIceAgeTarget(currentTarget, timestamp)
      target is Target.unsafe(currentTarget.value.shiftRight(i))
    }

    val period     = ALPH.ExpDiffPeriod.timesUnsafe(256)
    val timestamp1 = ALPH.LemanDifficultyBombEnabledTimestamp.plusUnsafe(period)
    calIceAgeTarget(currentTarget, timestamp1) is Target.Zero
  }

  trait SimulationFixture extends MockFixture {
    var currentTs = TimeStamp.now()
    val data = AVector.tabulate(2 * threshold) { _ =>
      currentTs = currentTs + consensusConfig.expectedTimeSpan
      BlockHash.random -> currentTs
    }
    setup(data)

    var currentHeight = chainInfo.values.map(_._1).max
    def addNew(hash: BlockHash, timestamp: TimeStamp) = {
      currentHeight += 1
      currentTs = timestamp
      chainInfo += hash -> (currentHeight -> timestamp)
    }

    val initialTarget =
      Target.unsafe(consensusConfig.maxMiningTarget.value.divide(BigInteger.valueOf(128)))
    var currentTarget =
      calNextHashTargetRaw(getHash(currentHeight), initialTarget, ALPH.LaunchTimestamp).rightValue
    currentTarget is initialTarget
    def stepSimulation(finalTarget: Target) = {
      val ratio =
        (BigDecimal(finalTarget.value) / BigDecimal(currentTarget.value)).toDouble
      val error    = (Random.nextDouble() - 0.5) / 20
      val duration = consensusConfig.expectedTimeSpan.millis * ratio * (1 + error)
      val nextTs   = currentTs.plusMillisUnsafe(duration.toLong)
      val newHash  = BlockHash.random
      addNew(newHash, nextTs)
      currentTarget = calNextHashTargetRaw(newHash, currentTarget, ALPH.LaunchTimestamp).rightValue
    }

    def checkRatio(ratio: Double, expected: Double) = {
      ratio >= expected * 0.95 && ratio <= expected * 1.05
    }
  }

  it should "simulate hashrate increasing" in new SimulationFixture {
    val finalTarget = Target.unsafe(initialTarget.value.divide(BigInteger.valueOf(100)))
    (0 until 1000).foreach { _ => stepSimulation(finalTarget) }
    val ratio = BigDecimal(initialTarget.value) / BigDecimal(currentTarget.value)
    checkRatio(ratio.toDouble, 100.0) is true
  }

  it should "simulate hashrate decreasing" in new SimulationFixture {
    val finalTarget = Target.unsafe(initialTarget.value.multiply(BigInteger.valueOf(100)))
    (0 until 1000).foreach { _ => stepSimulation(finalTarget) }
    val ratio = BigDecimal(currentTarget.value) / BigDecimal(initialTarget.value)
    checkRatio(ratio.toDouble, 100.0) is true
  }
}
