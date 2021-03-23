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
import org.alephium.util.{Duration, TimeStamp, U256}

class Emission(groupConfig: GroupConfig, blockTargetTime: Duration) {
  // scalastyle:off magic.number
  val yearsUntilStable: Int               = 4
  val blocksInAboutOneYear: Long          = Duration.ofDaysUnsafe(365L).millis / blockTargetTime.millis
  val blocksToStableMaxReward: Long       = blocksInAboutOneYear * yearsUntilStable
  val durationToStableMaxReward: Duration = blockTargetTime.timesUnsafe(blocksToStableMaxReward)
  // scalastyle:on magic.number

  val initialMaxRewardPerChain: U256         = share(Emission.initialMaxReward)
  val stableMaxRewardPerChain: U256          = share(Emission.stableMaxReward)
  val lowHashRateInitialRewardPerChain: U256 = share(Emission.lowHashRateInitialReward)

  val onePhPerSecondDivided: Target  = share(Target.onePhPerSecond)
  val oneEhPerSecondDivided: Target  = share(Target.oneEhPerSecond)
  val a128EhPerSecondDivided: Target = share(Target.a128EhPerSecond)

  val yearlyCentsDropUntilStable: Long = initialMaxRewardPerChain
    .subUnsafe(stableMaxRewardPerChain)
    .divUnsafe(ALF.cent(1))
    .divUnsafe(U256.unsafe(yearsUntilStable))
    .toBigInt
    .longValue()
  val blocksToDropAboutOneCent: Long        = blocksInAboutOneYear / yearlyCentsDropUntilStable
  val durationToDropAboutOnceCent: Duration = blockTargetTime.timesUnsafe(blocksToDropAboutOneCent)

  def share(amount: U256): U256 =
    amount.divUnsafe(U256.unsafe(groupConfig.chainNum))

  def share(target: Target): Target =
    Target.unsafe(target.value.divide(BigInteger.valueOf(groupConfig.chainNum.toLong)))

  def miningReward(header: BlockHeader): U256 =
    reward(header.target, header.timestamp, ALF.GenesisTimestamp)

  def reward(target: Target, blockTs: TimeStamp, genesisTs: TimeStamp): U256 = {
    val maxReward      = rewardMax(blockTs, genesisTs)
    val adjustedReward = rewardWrtTarget(target)
    if (maxReward > adjustedReward) adjustedReward else maxReward
  }

  def rewardMax(blockTs: TimeStamp, genesisTs: TimeStamp): U256 = {
    require(blockTs >= genesisTs)
    val elapsed = blockTs.deltaUnsafe(genesisTs)
    if (elapsed >= durationToStableMaxReward) {
      stableMaxRewardPerChain
    } else {
      val reducedCents = ALF.cent(elapsed.millis / durationToDropAboutOnceCent.millis)
      initialMaxRewardPerChain.subUnsafe(reducedCents)
    }
  }

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
}

object Emission {
  def apply(groupConfig: GroupConfig, blockTargetTime: Duration): Emission =
    new Emission(groupConfig, blockTargetTime)

  //scalastyle:off magic.number
  private[mining] val initialMaxReward: U256         = ALF.alf(60)
  private[mining] val stableMaxReward: U256          = ALF.alf(20)
  private[mining] val lowHashRateInitialReward: U256 = initialMaxReward.divUnsafe(U256.unsafe(2))
  //scalastyle:on magic.number
}
