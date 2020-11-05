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
import org.alephium.protocol.model.Target
import org.alephium.util.{Duration, TimeStamp, U256}

object Emission {
  //scalastyle:off magic.number
  val initialMaxReward: U256         = ALF.alf(15)
  val stableMaxReward: U256          = ALF.alf(3)
  val lowHashRateInitialReward: U256 = initialMaxReward.divUnsafe(U256.unsafe(2))

  val blockTargetTime: Duration             = Duration.ofSecondsUnsafe(64)
  val blocksInAboutOneYear: Long            = 365L * 24L * 60L * 60L * 1000L / blockTargetTime.millis
  val blocksToDropAboutOneCent: Long        = blocksInAboutOneYear / 300L
  val durationToDropAboutOnceCent: Duration = blockTargetTime.timesUnsafe(blocksToDropAboutOneCent)
  val blocksToStableMaxReward: Long         = blocksToDropAboutOneCent * 1200L
  val durationToStableMaxReward: Duration   = blockTargetTime.timesUnsafe(blocksToStableMaxReward)

  val onePhPerSecond: Target  = Target.unsafe(BigInteger.ONE.shiftLeft(50))
  val oneEhPerSecond: Target  = Target.unsafe(BigInteger.ONE.shiftLeft(60))
  val a128EhPerSecond: Target = Target.unsafe(BigInteger.ONE.shiftLeft(67))
  //scalastyle:on magic.number

  def reward(target: Target, blockTs: TimeStamp, genesisTs: TimeStamp): U256 = {
    val maxReward      = rewardMax(blockTs, genesisTs)
    val adjustedReward = rewardWrtTarget(target)
    if (maxReward > adjustedReward) adjustedReward else maxReward
  }

  def rewardMax(blockTs: TimeStamp, genesisTs: TimeStamp): U256 = {
    require(blockTs >= genesisTs)
    val elapsed = blockTs.deltaUnsafe(genesisTs)
    if (elapsed >= durationToStableMaxReward) {
      stableMaxReward
    } else {
      val reducedCents = ALF.cent(elapsed.millis / durationToDropAboutOnceCent.millis)
      initialMaxReward.subUnsafe(reducedCents)
    }
  }

  def rewardWrtTarget(target: Target): U256 = {
    if (target <= onePhPerSecond) {
      val rewardPlus = (initialMaxReward subUnsafe lowHashRateInitialReward).toBigInt
        .multiply(target.value)
        .divide(onePhPerSecond.value)
      lowHashRateInitialReward addUnsafe U256.unsafe(rewardPlus)
    } else if (target <= oneEhPerSecond) {
      val rewardReduce = (initialMaxReward subUnsafe stableMaxReward).toBigInt
        .multiply(target.value) // we don't subtract onePhPerSecond for simplification
        .divide(oneEhPerSecond.value)
      initialMaxReward subUnsafe U256.unsafe(rewardReduce)
    } else if (target <= a128EhPerSecond) {
      val rewardReduce = stableMaxReward.toBigInt
        .multiply(target.value) // we don't subtract oneEhPerSecond for simplification
        .divide(a128EhPerSecond.value)
      stableMaxReward subUnsafe U256.unsafe(rewardReduce)
    } else {
      U256.Zero
    }
  }
}
