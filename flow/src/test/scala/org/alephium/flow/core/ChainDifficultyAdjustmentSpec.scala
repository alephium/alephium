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

import akka.util.ByteString

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.setting.{ConsensusSetting, ConsensusSettings}
import org.alephium.io.IOResult
import org.alephium.protocol.ALPH
import org.alephium.protocol.config._
import org.alephium.protocol.model.{BlockHash, HardFork, NetworkId, Target}
import org.alephium.util.{AVector, Duration, NumericHelpers, TimeStamp}

class ChainDifficultyAdjustmentSpec extends AlephiumFlowSpec { Test =>
  import ChainDifficultyAdjustment._

  trait MockFixture extends ChainDifficultyAdjustment with NumericHelpers {
    def toConsensusSetting(config: ConsensusConfig): ConsensusSetting =
      ConsensusSetting(config.blockTargetTime, config.uncleDependencyGapTime, 18, config.emission)
    val consensusConfigs: ConsensusSettings = {
      val configFixture = (new ConsensusConfigsFixture.Default {}).consensusConfigs
      val mainnet       = toConsensusSetting(configFixture.mainnet)
      val ghost         = toConsensusSetting(configFixture.ghost)
      ConsensusSettings(mainnet, ghost, 25)
    }
    implicit override def networkConfig: NetworkConfig = NetworkConfigFixture.SinceLeman
    implicit val consensusConfig =
      if (Random.nextBoolean()) consensusConfigs.ghost else consensusConfigs.mainnet

    val enabledDurationAfterNow = Duration.ofDaysUnsafe(10)
    override val difficultyBombPatchConfig =
      new ChainDifficultyAdjustment.DifficultyBombPatchConfig {
        val enabledTimeStamp: TimeStamp = TimeStamp.now().plusUnsafe(enabledDurationAfterNow)
        val heightDiff                  = 100
      }

    val difficultyBombPatchTarget                = Target.unsafe(BigInteger.ONE.shiftLeft(100))
    def getTarget(height: Int): IOResult[Target] = Right(difficultyBombPatchTarget)

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
    def calTimeSpan(
        hash: BlockHash
    ): IOResult[Option[Duration]] = {
      calTimeSpan(hash, TimeStamp.now())
    }

    def calTimeSpan(
        hash: BlockHash,
        nextTimeStamp: TimeStamp
    ): IOResult[Option[Duration]] = {
      calTimeSpan(hash, getHeight(hash).rightValue, nextTimeStamp)
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
    calTimeSpan(data.last._1) isE Some(median1 deltaUnsafe median2)
  }

  it should "return initial target when few blocks" in {
    implicit val consensusConfig = consensusConfigs.mainnet
    val maxHeight                = ALPH.GenesisHeight + consensusConfig.powAveragingWindow + 1
    (1 until maxHeight).foreach { n =>
      val data       = AVector.fill(n)(BlockHash.random -> TimeStamp.zero)
      val fixture    = new MockFixture { setup(data) }
      val latestHash = data.last._1
      fixture.chainBackUntil(latestHash, 0) isE data.tail.map(_._1)
      val currentTarget = Target.unsafe(BigInteger.valueOf(Random.nextLong(Long.MaxValue)))
      fixture.calNextHashTargetRaw(
        latestHash,
        currentTarget,
        ALPH.LaunchTimestamp,
        TimeStamp.now()
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
      calTimeSpan(hash, height, TimeStamp.now()) isE Some(
        data(height)._2 deltaUnsafe data(height - 17)._2
      )
      calNextHashTargetRaw(
        hash,
        currentTarget,
        ALPH.LaunchTimestamp,
        TimeStamp.now()
      ) isE currentTarget
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
      calTimeSpan(hash, height, TimeStamp.now()) isE Some(
        data(height)._2 deltaUnsafe data(height - 17)._2
      )
      calNextHashTargetRaw(hash, currentTarget, ALPH.LaunchTimestamp, TimeStamp.now()) isE
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
      calTimeSpan(hash, height, TimeStamp.now()) isE Some(
        data(height)._2 deltaUnsafe data(height - 17)._2
      )
      calNextHashTargetRaw(hash, currentTarget, ALPH.LaunchTimestamp, TimeStamp.now()) isE
        reTarget(currentTarget, consensusConfig.windowTimeSpanMin.millis)
    }
  }

  it should "decrease the target when difficulty bomb enabled" in new MockFixture {
    implicit override def networkConfig: NetworkConfig = new NetworkConfig {
      override def networkId: NetworkId       = NetworkId.AlephiumMainNet
      override def noPreMineProof: ByteString = ByteString.empty
      override def lemanHardForkTimestamp: TimeStamp =
        ALPH.DifficultyBombPatchEnabledTimeStamp.plusHoursUnsafe(100)
      def ghostHardForkTimestamp: TimeStamp = TimeStamp.unsafe(Long.MaxValue)
    }

    final def calIceAgeTarget(
        currentTarget: Target,
        currentTimeStamp: TimeStamp
    ): Target = {
      calIceAgeTarget(
        currentTarget,
        currentTimeStamp,
        currentTimeStamp.plusUnsafe(Duration.ofSecondsUnsafe(1))
      )
    }

    val currentTarget           = consensusConfig.maxMiningTarget
    val diffBombEnabledTs       = ALPH.PreLemanDifficultyBombEnabledTimestamp
    val diffBombFirstAdjustment = diffBombEnabledTs.plusUnsafe(ALPH.ExpDiffPeriod)

    info("Before the diff bomb")
    calIceAgeTarget(
      currentTarget,
      diffBombEnabledTs.minusUnsafe(Duration.ofSecondsUnsafe(1))
    ) is currentTarget

    info("The diff bomb is enabled, no change for the first period")
    calIceAgeTarget(
      currentTarget,
      diffBombEnabledTs
    ) is currentTarget
    calIceAgeTarget(
      currentTarget,
      diffBombFirstAdjustment.minusUnsafe(Duration.ofSecondsUnsafe(1))
    ) is currentTarget

    info("The diff bomb is enabled and diff starts to change too")
    calIceAgeTarget(
      currentTarget,
      diffBombFirstAdjustment
    ) isnot currentTarget

    info("The diff bomb patch is not enabled yet")
    calIceAgeTarget(
      currentTarget,
      ALPH.DifficultyBombPatchEnabledTimeStamp.minusUnsafe(Duration.ofSecondsUnsafe(2)),
      ALPH.DifficultyBombPatchEnabledTimeStamp.minusUnsafe(Duration.ofSecondsUnsafe(1))
    ) isnot currentTarget

    info("The diff bomb patch is enabled")
    calIceAgeTarget(
      currentTarget,
      ALPH.DifficultyBombPatchEnabledTimeStamp.minusUnsafe(Duration.ofSecondsUnsafe(1)),
      ALPH.DifficultyBombPatchEnabledTimeStamp
    ) is currentTarget

    info("Leman hardfork is not enabled yet")
    val lemanNotEnabledTs =
      networkConfig.lemanHardForkTimestamp.minusUnsafe(Duration.ofSecondsUnsafe(1))
    networkConfig.getHardFork(lemanNotEnabledTs) is HardFork.Mainnet
    calIceAgeTarget(
      currentTarget,
      lemanNotEnabledTs
    ) is currentTarget

    info("Leman hardfork is enabled yet")
    networkConfig.getHardFork(networkConfig.lemanHardForkTimestamp) is HardFork.Leman
    calIceAgeTarget(
      currentTarget,
      networkConfig.lemanHardForkTimestamp
    ) is currentTarget
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
      calNextHashTargetRaw(
        getHash(currentHeight),
        initialTarget,
        ALPH.LaunchTimestamp,
        TimeStamp.now()
      ).rightValue
    currentTarget is initialTarget
    def stepSimulation(finalTarget: Target) = {
      val ratio =
        (BigDecimal(finalTarget.value) / BigDecimal(currentTarget.value)).toDouble
      val error    = (Random.nextDouble() - 0.5) / 20
      val duration = consensusConfig.expectedTimeSpan.millis * ratio * (1 + error)
      val nextTs   = currentTs.plusMillisUnsafe(duration.toLong)
      val newHash  = BlockHash.random
      addNew(newHash, nextTs)
      currentTarget = calNextHashTargetRaw(
        newHash,
        currentTarget,
        ALPH.LaunchTimestamp,
        TimeStamp.now()
      ).rightValue
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

  it should "test difficulty bomb patch" in new MockFixture {
    val enabledTimeStamp = difficultyBombPatchConfig.enabledTimeStamp
    val data0 = AVector.tabulate(threshold)(_ =>
      BlockHash.random -> enabledTimeStamp.minusUnsafe(Duration.ofMinutesUnsafe(1))
    )
    val data1 = AVector.tabulate(threshold) { i =>
      val timestamp = enabledTimeStamp.minusUnsafe(Duration.ofSecondsUnsafe((threshold - i).toLong))
      BlockHash.random -> timestamp
    }
    val data2 = AVector.tabulate(threshold - 1) { i =>
      val timestamp = enabledTimeStamp.plusUnsafe(Duration.ofSecondsUnsafe(i.toLong))
      BlockHash.random -> timestamp
    }
    val data3 = AVector.tabulate(threshold) { i =>
      val timestamp = enabledTimeStamp.plusUnsafe(Duration.ofSecondsUnsafe(i.toLong))
      BlockHash.random -> timestamp
    }
    val data = data0 ++ data1 ++ data2 ++ data3
    setup(data)

    val currentTarget = Target.unsafe(BigInteger.valueOf(1024))
    (threshold until threshold * 2).foreach { height =>
      val hash = getHash(height)
      calTimeSpan(hash, height, getTimestamp(hash).rightValue) isE
        Some(data(height)._2 deltaUnsafe data(height - 17)._2)
      calNextHashTargetRaw(
        hash,
        currentTarget,
        ALPH.LaunchTimestamp,
        getTimestamp(hash).rightValue
      ) isE
        reTarget(currentTarget, consensusConfig.windowTimeSpanMin.millis)
    }

    (threshold * 2 until threshold * 3 - 1).foreach { height =>
      val hash = getHash(height)
      calTimeSpan(hash, height, getTimestamp(hash).rightValue) isE None
      calNextHashTargetRaw(
        hash,
        currentTarget,
        ALPH.LaunchTimestamp,
        getTimestamp(hash).rightValue
      ) isE difficultyBombPatchTarget
    }

    ((threshold * 3 - 1) until (threshold * 4 - 1)).foreach { height =>
      val hash = getHash(height)
      calTimeSpan(hash, height, getTimestamp(hash).rightValue) isE Some(
        data(height)._2 deltaUnsafe data(height - 17)._2
      )
      calNextHashTargetRaw(
        hash,
        currentTarget,
        ALPH.LaunchTimestamp,
        getTimestamp(hash).rightValue
      ) isE
        reTarget(currentTarget, consensusConfig.windowTimeSpanMin.millis)
    }
  }
}
