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

  val onePhPerSecondDivided: HashRate  = shareHashRate(HashRate.onePhPerSecond)
  val oneEhPerSecondDivided: HashRate  = shareHashRate(HashRate.oneEhPerSecond)
  val a128EhPerSecondDivided: HashRate = shareHashRate(HashRate.a128EhPerSecond)

  val onePhPerSecondDividedTarget: Target  = Target.from(onePhPerSecondDivided, blockTargetTime)
  val oneEhPerSecondDividedTarget: Target  = Target.from(oneEhPerSecondDivided, blockTargetTime)
  val a128EhPerSecondDividedTarget: Target = Target.from(a128EhPerSecondDivided, blockTargetTime)

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

  def reward(header: BlockHeader): Emission.RewardType =
    reward(header.target, header.timestamp, ALF.LaunchTimestamp)

  def reward(powTarget: Target, blockTs: TimeStamp, launchTs: TimeStamp): Emission.RewardType = {
    val timeBasedReward = rewardWrtTime(blockTs, launchTs)
    if (shouldEnablePoLW(powTarget)) {
      val polwTarget        = polwTargetUnsafe(powTarget)
      val targetBasedReward = rewardWrtTarget(polwTarget)
      val miningReward      = Math.min(timeBasedReward, targetBasedReward)
      val burntAmount       = burntAmountUnsafe(polwTarget, miningReward)
      Emission.PoLW(miningReward, burntAmount)
    } else {
      val targetBasedReward = rewardWrtTarget(powTarget)
      val miningReward      = Math.min(timeBasedReward, targetBasedReward)
      Emission.PoW(miningReward)
    }
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

  val onePhPerSecondDividedRank: Int  = onePhPerSecondDivided.value.bitLength() - 1
  val oneEhPerSecondDividedRank: Int  = oneEhPerSecondDivided.value.bitLength() - 1
  val a128EhPerSecondDividedRank: Int = a128EhPerSecondDivided.value.bitLength() - 1

  val onePhPhaseRewardGap: BigInteger = initialMaxRewardPerChain.v
  val oneEhPhaseRewardGap: BigInteger =
    stableMaxRewardPerChain.v.subtract(initialMaxRewardPerChain.v)
  val a128EHPhaseRewardGap: BigInteger = BigInteger.ZERO.subtract(stableMaxRewardPerChain.v)

  val onePhPerSecondStepReward: BigInteger = onePhPhaseRewardGap
    .divide(BigInteger.valueOf(onePhPerSecondDividedRank.toLong))
  val oneEhPerSecondStepReward: BigInteger = oneEhPhaseRewardGap
    .divide(BigInteger.valueOf((oneEhPerSecondDividedRank - onePhPerSecondDividedRank).toLong))
  val a128EHPerSecondStepReward: BigInteger = a128EHPhaseRewardGap
    .divide(BigInteger.valueOf((a128EhPerSecondDividedRank - oneEhPerSecondDividedRank).toLong))

  // mining reward with respect to hashrate
  def rewardWrtHashRate(hashRate: HashRate): U256 = {
    if (hashRate < onePhPerSecondDivided) {
      val reward = Emission.rewardWrtHashRate(
        hashRate,
        0,
        onePhPerSecondDividedRank,
        BigInteger.ZERO,
        onePhPhaseRewardGap,
        onePhPerSecondStepReward
      )
      Math.max(lowHashRateInitialRewardPerChain, reward)
    } else if (hashRate < oneEhPerSecondDivided) {
      Emission.rewardWrtHashRate(
        hashRate,
        onePhPerSecondDividedRank,
        oneEhPerSecondDividedRank,
        initialMaxRewardPerChain.v,
        oneEhPhaseRewardGap,
        oneEhPerSecondStepReward
      )
    } else if (hashRate < a128EhPerSecondDivided) {
      Emission.rewardWrtHashRate(
        hashRate,
        oneEhPerSecondDividedRank,
        a128EhPerSecondDividedRank,
        stableMaxRewardPerChain.v,
        a128EHPhaseRewardGap,
        a128EHPerSecondStepReward
      )
    } else {
      U256.Zero
    }
  }

  // mining reward with respect to target
  def rewardWrtTarget(target: Target): U256 = {
    rewardWrtHashRate(HashRate.from(target, blockTargetTime))
  }

  def shouldEnablePoLW(target: Target): Boolean = {
    target < oneEhPerSecondDividedTarget
  }

  // the amount to burn is reward * 7/8 (1 - 1Eh/s / HR)
  def burntAmountUnsafe(target: Target, miningReward: U256): U256 = {
    assume(shouldEnablePoLW(target))
    val amount = miningReward.toBigInt
      .multiply(oneEhPerSecondDividedTarget.value.subtract(target.value))
      .divide(oneEhPerSecondDividedTarget.value)
      .multiply(BigInteger.valueOf(7))
      .divide(BigInteger.valueOf(8))
    U256.unsafe(amount)
  }

  private val sevenEhPerSecondDivided: HashRate = oneEhPerSecondDivided.multiply(7)

  // the true hash rate for energy-based mining is (HR * 1/8 + 1Eh/s * 7/8)
  // we could reduce energy consumption by ~7/8 when hash rate is significantly high
  def polwTargetUnsafe(powTarget: Target): Target = {
    assume(shouldEnablePoLW(powTarget))
    val powHashRate  = HashRate.from(powTarget, blockTargetTime)
    val polwHashRate = powHashRate.multiply(8).subtractUnsafe(sevenEhPerSecondDivided)
    val polwTarget   = Target.from(polwHashRate, blockTargetTime)
    polwTarget
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
    (4 to 67).map { order =>
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

  sealed trait RewardType {
    def miningReward: U256
  }
  final case class PoW(miningReward: U256)                     extends RewardType
  final case class PoLW(miningReward: U256, burntAmount: U256) extends RewardType

  @inline def rank(hashRate: HashRate): Int = {
    hashRate.value.bitLength() - 1
  }

  @inline def baseReward(
      rank: Int,
      startRank: Int,
      endRank: Int,
      startReward: BigInteger,
      phaseRewardGap: BigInteger
  ): BigInteger = {
    startReward.add(
      phaseRewardGap
        .multiply(BigInteger.valueOf((rank - startRank).toLong))
        .divide(BigInteger.valueOf((endRank - startRank).toLong))
    )
  }

  @inline def deltaReward(
      hashRate: BigInteger,
      stepHashRate: BigInteger,
      stepReward: BigInteger
  ): BigInteger = {
    stepReward.multiply(hashRate.mod(stepHashRate)).divide(stepHashRate)
  }

  @inline def stepHashRate(stepRank: Int): BigInteger = BigInteger.ONE.shiftLeft(stepRank)

  def rewardWrtHashRate(
      hashRate: HashRate,
      startRank: Int,
      endRank: Int,
      startReward: BigInteger,
      phaseRewardGap: BigInteger,
      stepReward: BigInteger
  ): U256 = {
    val stepRank     = Emission.rank(hashRate)
    val stepHashRate = Emission.stepHashRate(stepRank)
    val baseReward   = Emission.baseReward(stepRank, startRank, endRank, startReward, phaseRewardGap)
    val deltaReward  = Emission.deltaReward(hashRate.value, stepHashRate, stepReward)
    assume(stepRank >= startRank && stepRank < endRank)
    U256.unsafe(baseReward.add(deltaReward))
  }
}
