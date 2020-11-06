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

import org.scalatest.Assertion

import org.alephium.protocol.ALF
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.Target
import org.alephium.util.{AlephiumSpec, Duration, NumericHelpers, TimeStamp, U256}

class EmissionSpec extends AlephiumSpec with NumericHelpers {
  import Emission._

  def equalLong(x: Long, y: Long, errorOrder: Long = 8): Assertion = {
    val errorBase = math.pow(10.0, errorOrder.toDouble).toLong
    (x >= y * (errorBase - 1) / errorBase && x <= y * (errorBase + 1) / errorBase) is true
  }

  def equalU256(x: U256, y: U256, errorOrder: Long = 8): Assertion = {
    val errorBase = math.pow(10.0, errorOrder.toDouble).toLong
    (x >= y * (errorBase - 1) / errorBase && x <= y * (errorBase + 1) / errorBase) is true
  }

  def getInflationRate(amount: U256): Double = {
    (BigDecimal(amount.toBigInt) / BigDecimal(ALF.MaxALFValue.toBigInt)).doubleValue
  }

  trait Fixture {
    val groupConfig = new GroupConfig {
      override def groups: Int = 2
    }
    val emission = Emission(groupConfig)
  }

  it should "compute correct constants" in new Fixture {
    blocksInAboutOneYear is 492750
    durationToStableMaxReward is Duration.ofHoursUnsafe(4 * 365 * 24)

    val maxRewards = blocksInAboutOneYear.mulUnsafe(Emission.initialMaxReward)
    val maxRate    = getInflationRate(maxRewards)
    (maxRate > 0.029 && maxRate < 0.03) is true

    val stableRewards = blocksInAboutOneYear.mulUnsafe(Emission.stableMaxReward)
    val stableRate    = getInflationRate(stableRewards)
    (stableRate > 0.0098 && stableRate < 0.0099) is true

    onePhPerSecond.value is BigInteger.valueOf(1024).pow(5)
    oneEhPerSecond.value is BigInteger.valueOf(1024).pow(6)
    a128EhPerSecond.value is BigInteger.valueOf(1024).pow(6).multiply(BigInteger.valueOf(128))
  }

  it should "compute max reward based on timestamp" in new Fixture {
    import emission._

    rewardMax(TimeStamp.zero, TimeStamp.zero) is initialMaxRewardPerChain
    rewardMax(TimeStamp.zero + Duration.ofHoursUnsafe(1 * 365 * 24), TimeStamp.zero) is
      ALF.cent(1250)
    rewardMax(TimeStamp.zero + Duration.ofHoursUnsafe(2 * 365 * 24), TimeStamp.zero) is ALF.alf(10)
    rewardMax(TimeStamp.zero + Duration.ofHoursUnsafe(3 * 365 * 24), TimeStamp.zero) is
      ALF.cent(750)
    rewardMax(TimeStamp.zero + Duration.ofHoursUnsafe(4 * 365 * 24), TimeStamp.zero) is ALF.alf(5)
  }

  def average(target0: Target, target1: Target): Target = {
    Target.unsafe(target0.value.add(target1.value).divide(BigInteger.TWO))
  }

  def average(reward0: U256, reward1: U256): U256 = {
    reward0.addUnsafe(reward1).divUnsafe(U256.Two)
  }

  it should "compute reward based on target" in new Fixture {
    import emission._

    rewardWrtTarget(Target.unsafe(BigInteger.ZERO)) is lowHashRateInitialRewardPerChain
    equalU256(rewardWrtTarget(Target.unsafe(BigInteger.ONE)), lowHashRateInitialRewardPerChain)
    rewardWrtTarget(onePhPerSecondDivided) is initialMaxRewardPerChain
    rewardWrtTarget(oneEhPerSecondDivided) is stableMaxRewardPerChain
    rewardWrtTarget(a128EhPerSecondDivided) is U256.Zero

    rewardWrtTarget(average(Target.unsafe(BigInteger.ZERO), onePhPerSecondDivided)) is
      average(lowHashRateInitialRewardPerChain, initialMaxRewardPerChain)
    rewardWrtTarget(average(Target.unsafe(BigInteger.ZERO), oneEhPerSecondDivided)) is
      average(initialMaxRewardPerChain, stableMaxRewardPerChain)
    rewardWrtTarget(average(Target.unsafe(BigInteger.ZERO), a128EhPerSecondDivided)) is
      average(stableMaxRewardPerChain, U256.Zero)
  }

  it should "take the least reward" in new Fixture {
    import emission._

    reward(Target.unsafe(BigInteger.ZERO), TimeStamp.zero, TimeStamp.zero) is lowHashRateInitialRewardPerChain
    reward(onePhPerSecondDivided,
           TimeStamp.zero + Duration.ofHoursUnsafe(4 * 365 * 24),
           TimeStamp.zero) is stableMaxRewardPerChain
  }
}
