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

package org.alephium.protocol.mining

import java.math.BigInteger

import org.alephium.protocol.ALF
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHeader, Target}
import org.alephium.util.{Duration, Math, TimeStamp, U256}

class Emission(groupConfig: GroupConfig, blockTargetTime: Duration) {
  import Emission.{yearsUntilNoReward, yearsUntilStable}

  // scalastyle:off magic.number
  val blocksInAboutOneYearPerChain: Long =
    Duration.ofDaysUnsafe(365L).millis / blockTargetTime.millis
  val blocksToStableMaxReward: Long       = blocksInAboutOneYearPerChain * yearsUntilStable
  val blocksToNoReward: Long              = blocksInAboutOneYearPerChain * yearsUntilNoReward
  val durationToStableMaxReward: Duration = blockTargetTime.timesUnsafe(blocksToStableMaxReward)
  val durationToNoReward: Duration        = blockTargetTime.timesUnsafe(blocksToNoReward)
  // scalastyle:on magic.number

  val initialMaxRewardPerChain: U256         = shareReward(Emission.initialMaxReward)
  val stableMaxRewardPerChain: U256          = shareReward(Emission.stableMaxReward)
  val lowHashRateInitialRewardPerChain: U256 = shareReward(Emission.lowHashRateInitialReward)

  val onePhPerSecondDivided: Target =
    Target.from(shareHashRate(HashRate.onePhPerSecond), blockTargetTime)
  val oneEhPerSecondDivided: Target =
    Target.from(shareHashRate(HashRate.oneEhPerSecond), blockTargetTime)
  val a128EhPerSecondDivided: Target =
    Target.from(shareHashRate(HashRate.a128EhPerSecond), blockTargetTime)

  val yearlyCentsDropUntilStable: Long = initialMaxRewardPerChain
    .subUnsafe(stableMaxRewardPerChain)
    .divUnsafe(ALF.cent(1))
    .divUnsafe(U256.unsafe(yearsUntilStable))
    .toBigInt
    .longValue()
  val blocksToDropAboutOneCent: Long        = blocksInAboutOneYearPerChain / yearlyCentsDropUntilStable
  val durationToDropAboutOnceCent: Duration = blockTargetTime.timesUnsafe(blocksToDropAboutOneCent)

  def shareReward(amount: U256): U256 =
    amount.divUnsafe(U256.unsafe(groupConfig.chainNum))

  def shareHashRate(hashRate: HashRate): HashRate =
    HashRate.unsafe(hashRate.value.divide(BigInteger.valueOf(groupConfig.chainNum.toLong)))

  def miningReward(header: BlockHeader): U256 =
    reward(header.target, header.timestamp, ALF.LaunchTimestamp)

  def reward(target: Target, blockTs: TimeStamp, launchTs: TimeStamp): U256 = {
    val timeBasedReward = rewardWrtTime(blockTs, launchTs)
    val adjustedReward  = rewardWrtTarget(target)
    Math.min(timeBasedReward, adjustedReward)
  }

  // mining reward with respect to time
  def rewardWrtTime(blockTs: TimeStamp, launchTs: TimeStamp): U256 = {
    require(blockTs >= launchTs)
    val elapsed = blockTs.deltaUnsafe(launchTs)
    if (elapsed >= durationToNoReward) {
      U256.Zero
    } else if (elapsed >= durationToStableMaxReward) {
      stableMaxRewardPerChain
    } else {
      val reducedCents = ALF.cent(elapsed.millis / durationToDropAboutOnceCent.millis)
      initialMaxRewardPerChain.subUnsafe(reducedCents)
    }
  }

  // mining reward with respect to target (or hash rate)
  def rewardWrtTarget(target: Target): U256 = {
    if (target >= onePhPerSecondDivided) {
      val rewardPlus =
        (initialMaxRewardPerChain subUnsafe lowHashRateInitialRewardPerChain).toBigInt
          .multiply(onePhPerSecondDivided.value)
          .divide(target.value)
      lowHashRateInitialRewardPerChain addUnsafe U256.unsafe(rewardPlus)
    } else if (target >= oneEhPerSecondDivided) {
      val rewardReduce = (initialMaxRewardPerChain subUnsafe stableMaxRewardPerChain).toBigInt
        .multiply(
          oneEhPerSecondDivided.value
        ) // we don't subtract onePhPerSecond for simplification
        .divide(target.value)
      initialMaxRewardPerChain subUnsafe U256.unsafe(rewardReduce)
    } else if (target >= a128EhPerSecondDivided) {
      val rewardReduce = stableMaxRewardPerChain.toBigInt
        .multiply(
          a128EhPerSecondDivided.value
        ) // we don't subtract oneEhPerSecond for simplification
        .divide(target.value)
      stableMaxRewardPerChain subUnsafe U256.unsafe(rewardReduce)
    } else {
      U256.Zero
    }
  }

  def canEnablePoLW(target: Target): Boolean = {
    target < oneEhPerSecondDivided
  }

  // the amount to burn is reward * 7/8 (1 - 1Eh/s / HR)
  // to encourage burning, we reduce the ratio from 7/8 to 3/4
  def burntAmountUnsafe(target: Target, miningReward: U256): U256 = {
    assume(canEnablePoLW(target))
    val amount = miningReward.toBigInt
      .multiply(oneEhPerSecondDivided.value.subtract(target.value))
      .divide(oneEhPerSecondDivided.value)
      .multiply(BigInteger.valueOf(3))
      .divide(BigInteger.valueOf(4))
    U256.unsafe(amount)
  }

  val oneEhPerSecondDividedHashRate: HashRate =
    HashRate.from(oneEhPerSecondDivided, blockTargetTime)

  // the true hash rate for energy-based mining is (HR * 1/8 + 1Eh/s * 7/8)
  // we could reduce energy consumption by ~1/8 when hash rate is significantly high
  def poLWTargetUnsafe(target: Target): Target = {
    assume(canEnablePoLW(target))
    val hashRate = HashRate.from(target, blockTargetTime)
    val powHashRate =
      hashRate.value
        .add(oneEhPerSecondDividedHashRate.value.multiply(BigInteger.valueOf(7)))
        .divide(BigInteger.valueOf(8))
    val powTarget = Target.from(HashRate.unsafe(powHashRate), blockTargetTime)
    powTarget
  }

  private def calRewardsPerYear(rewardPerChain: U256) = {
    val rewardAllChains = rewardPerChain.mulUnsafe(U256.unsafe(groupConfig.chainNum))
    rewardAllChains.mulUnsafe(U256.unsafe(blocksInAboutOneYearPerChain))
  }

  // scalastyle:off magic.number
  def rewardsWrtTime(): IndexedSeq[(Int, U256)] = {
    (0 to yearsUntilNoReward).map { k =>
      val time0          = ALF.LaunchTimestamp.plusHoursUnsafe((k * 24 * 365).toLong)
      val time1          = ALF.LaunchTimestamp.plusHoursUnsafe(((k + 1) * 24 * 365).toLong)
      val reward0        = rewardWrtTime(time0, ALF.LaunchTimestamp)
      val reward1        = rewardWrtTime(time1, ALF.LaunchTimestamp)
      val rewardPerChain = reward0.addUnsafe(reward1).divUnsafe(U256.Two)
      val rewardsPerYear = calRewardsPerYear(rewardPerChain)
      (k + 1) -> rewardsPerYear
    }
  }

  def rewardsWrtTarget(): IndexedSeq[(HashRate, U256)] = {
    (10 to 67).map { order =>
      val hashRate       = HashRate.unsafe(BigInteger.ONE.shiftLeft(order))
      val target         = Target.from(shareHashRate(hashRate), blockTargetTime)
      val rewardPerChain = rewardWrtTarget(target)
      val rewardsPerYear = calRewardsPerYear(rewardPerChain)
      hashRate -> rewardsPerYear
    }
  }
  // scalastyle:on magic.number
}

object Emission {
  def apply(groupConfig: GroupConfig, blockTargetTime: Duration): Emission =
    new Emission(groupConfig, blockTargetTime)

  //scalastyle:off magic.number
  private[mining] val initialMaxReward: U256         = ALF.alf(60)
  private[mining] val stableMaxReward: U256          = ALF.alf(20)
  private[mining] val lowHashRateInitialReward: U256 = initialMaxReward.divUnsafe(U256.unsafe(2))

  val yearsUntilStable: Int   = 4
  val yearsUntilNoReward: Int = 82
  //scalastyle:on magic.number
}
