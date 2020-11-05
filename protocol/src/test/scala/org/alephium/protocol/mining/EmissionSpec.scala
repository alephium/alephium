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

  it should "compute correct constants" in {
    blocksInAboutOneYear is 492750
    equalLong(durationToStableMaxReward.millis, Duration.ofHoursUnsafe(4 * 365 * 24).millis, 3)

    onePhPerSecond.value is BigInteger.valueOf(1024).pow(5)
    oneEhPerSecond.value is BigInteger.valueOf(1024).pow(6)
    a128EhPerSecond.value is BigInteger.valueOf(1024).pow(6).multiply(BigInteger.valueOf(128))
  }

  it should "compute max reward based on timestamp" in {
    rewardMax(TimeStamp.zero, TimeStamp.zero) is initialMaxReward
    rewardMax(TimeStamp.zero + Duration.ofHoursUnsafe(1 * 365 * 24), TimeStamp.zero) is ALF.alf(12)
    rewardMax(TimeStamp.zero + Duration.ofHoursUnsafe(2 * 365 * 24), TimeStamp.zero) is ALF.alf(9)
    rewardMax(TimeStamp.zero + Duration.ofHoursUnsafe(3 * 365 * 24), TimeStamp.zero) is ALF.alf(6)
    rewardMax(TimeStamp.zero + Duration.ofHoursUnsafe(4 * 365 * 24), TimeStamp.zero) is ALF.alf(3)
  }

  def average(target0: Target, target1: Target): Target = {
    Target.unsafe(target0.value.add(target1.value).divide(BigInteger.TWO))
  }

  def average(reward0: U256, reward1: U256): U256 = {
    reward0.addUnsafe(reward1).divUnsafe(U256.Two)
  }

  it should "compute reward based on target" in {
    rewardWrtTarget(Target.unsafe(BigInteger.ZERO)) is lowHashRateInitialReward
    equalU256(rewardWrtTarget(Target.unsafe(BigInteger.ONE)), lowHashRateInitialReward)
    rewardWrtTarget(onePhPerSecond) is initialMaxReward
    rewardWrtTarget(oneEhPerSecond) is stableMaxReward
    rewardWrtTarget(a128EhPerSecond) is U256.Zero

    rewardWrtTarget(average(Target.unsafe(BigInteger.ZERO), onePhPerSecond)) is
      average(lowHashRateInitialReward, initialMaxReward)
    rewardWrtTarget(average(Target.unsafe(BigInteger.ZERO), oneEhPerSecond)) is
      average(initialMaxReward, stableMaxReward)
    rewardWrtTarget(average(Target.unsafe(BigInteger.ZERO), a128EhPerSecond)) is
      average(stableMaxReward, U256.Zero)
  }

  it should "take the least reward" in {
    reward(Target.unsafe(BigInteger.ZERO), TimeStamp.zero, TimeStamp.zero) is lowHashRateInitialReward
    reward(onePhPerSecond, TimeStamp.zero + Duration.ofHoursUnsafe(4 * 365 * 24), TimeStamp.zero) is stableMaxReward
  }
}
