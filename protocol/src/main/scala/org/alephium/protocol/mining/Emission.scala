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

import org.alephium.protocol.ALPH
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHeader, Target}
import org.alephium.util.{Duration, Math, TimeStamp, U256}

class Emission(blockTargetTime: Duration)(implicit groupConfig: GroupConfig) {
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

  val onePhPerSecondTarget: Target  = Target.from(HashRate.onePhPerSecond, blockTargetTime)
  val oneEhPerSecondTarget: Target  = Target.from(HashRate.oneEhPerSecond, blockTargetTime)
  val a128EhPerSecondTarget: Target = Target.from(HashRate.a128EhPerSecond, blockTargetTime)

  val yearlyCentsDropUntilStable: Long = initialMaxRewardPerChain
    .subUnsafe(stableMaxRewardPerChain)
    .divUnsafe(ALPH.cent(1))
    .divUnsafe(U256.unsafe(yearsUntilStable))
    .toBigInt
    .longValue()
  val blocksToDropAboutOneCent: Long        = blocksInAboutOneYearPerChain / yearlyCentsDropUntilStable
  val durationToDropAboutOnceCent: Duration = blockTargetTime.timesUnsafe(blocksToDropAboutOneCent)

  def shareReward(amount: U256): U256 =
    amount.divUnsafe(U256.unsafe(groupConfig.chainNum))

  def reward(header: BlockHeader): Emission.RewardType =
    reward(header.target, header.timestamp, ALPH.LaunchTimestamp)

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
      val reducedCents = ALPH.cent(elapsed.millis / durationToDropAboutOnceCent.millis)
      initialMaxRewardPerChain.subUnsafe(reducedCents)
    }
  }

  val onePhPerSecondRank: Int  = HashRate.onePhPerSecond.value.bitLength() - 1
  val oneEhPerSecondRank: Int  = HashRate.oneEhPerSecond.value.bitLength() - 1
  val a128EhPerSecondRank: Int = HashRate.a128EhPerSecond.value.bitLength() - 1

  val onePhPhaseRewardGap: BigInteger = initialMaxRewardPerChain.v
  val oneEhPhaseRewardGap: BigInteger =
    stableMaxRewardPerChain.v.subtract(initialMaxRewardPerChain.v)
  val a128EHPhaseRewardGap: BigInteger = BigInteger.ZERO.subtract(stableMaxRewardPerChain.v)

  val onePhPerSecondStepReward: BigInteger = onePhPhaseRewardGap
    .divide(BigInteger.valueOf(onePhPerSecondRank.toLong))
  val oneEhPerSecondStepReward: BigInteger = oneEhPhaseRewardGap
    .divide(BigInteger.valueOf((oneEhPerSecondRank - onePhPerSecondRank).toLong))
  val a128EHPerSecondStepReward: BigInteger = a128EHPhaseRewardGap
    .divide(BigInteger.valueOf((a128EhPerSecondRank - oneEhPerSecondRank).toLong))

  // mining reward with respect to hashrate
  def rewardWrtHashRate(hashRate: HashRate): U256 = {
    if (hashRate < HashRate.onePhPerSecond) {
      val reward = Emission.rewardWrtHashRate(
        hashRate,
        0,
        onePhPerSecondRank,
        BigInteger.ZERO,
        onePhPhaseRewardGap,
        onePhPerSecondStepReward
      )
      Math.max(lowHashRateInitialRewardPerChain, reward)
    } else if (hashRate < HashRate.oneEhPerSecond) {
      Emission.rewardWrtHashRate(
        hashRate,
        onePhPerSecondRank,
        oneEhPerSecondRank,
        initialMaxRewardPerChain.v,
        oneEhPhaseRewardGap,
        oneEhPerSecondStepReward
      )
    } else if (hashRate < HashRate.a128EhPerSecond) {
      Emission.rewardWrtHashRate(
        hashRate,
        oneEhPerSecondRank,
        a128EhPerSecondRank,
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
    target < oneEhPerSecondTarget
  }

  // the amount to burn is reward * 7/8 (1 - 1Eh/s / HR)
  def burntAmountUnsafe(target: Target, miningReward: U256): U256 = {
    assume(shouldEnablePoLW(target))
    val amount = miningReward.toBigInt
      .multiply(oneEhPerSecondTarget.value.subtract(target.value))
      .divide(oneEhPerSecondTarget.value)
      .multiply(BigInteger.valueOf(7))
      .divide(BigInteger.valueOf(8))
    U256.unsafe(amount)
  }

  private val sevenEhPerSecond: HashRate = HashRate.oneEhPerSecond.multiply(7)

  // the true hash rate for energy-based mining is (HR * 1/8 + 1Eh/s * 7/8)
  // we could reduce energy consumption by ~7/8 when hash rate is significantly high
  def polwTargetUnsafe(powTarget: Target): Target = {
    assume(shouldEnablePoLW(powTarget))
    val powHashRate  = HashRate.from(powTarget, blockTargetTime)
    val polwHashRate = powHashRate.multiply(8).subtractUnsafe(sevenEhPerSecond)
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
      val time0          = ALPH.LaunchTimestamp.plusHoursUnsafe((k * 24 * 365).toLong)
      val time1          = ALPH.LaunchTimestamp.plusHoursUnsafe(((k + 1) * 24 * 365).toLong)
      val reward0        = rewardWrtTime(time0, ALPH.LaunchTimestamp)
      val reward1        = rewardWrtTime(time1, ALPH.LaunchTimestamp)
      val rewardPerChain = reward0.addUnsafe(reward1).divUnsafe(U256.Two)
      val rewardsPerYear = calRewardsPerYear(rewardPerChain)
      (k + 1) -> rewardsPerYear
    }
  }

  def rewardsWrtTarget(): IndexedSeq[(HashRate, U256)] = {
    (8 to 67).map { order =>
      val hashRate       = HashRate.unsafe(BigInteger.ONE.shiftLeft(order))
      val target         = Target.from(hashRate, blockTargetTime)
      val rewardPerChain = rewardWrtTarget(target)
      val rewardsPerYear = calRewardsPerYear(rewardPerChain)
      hashRate -> rewardsPerYear
    }
  }
  // scalastyle:on magic.number
}

object Emission {
  def apply(groupConfig: GroupConfig, blockTargetTime: Duration): Emission =
    new Emission(blockTargetTime)(groupConfig)

  //scalastyle:off magic.number
  private[mining] val initialMaxReward: U256         = ALPH.alph(60)
  private[mining] val stableMaxReward: U256          = ALPH.alph(20)
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
