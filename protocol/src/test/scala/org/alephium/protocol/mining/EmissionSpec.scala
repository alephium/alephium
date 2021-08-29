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

import scala.io.Source
import scala.util.Using

import org.scalatest.Assertion

import org.alephium.protocol.ALF
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.Target
import org.alephium.util.{AlephiumSpec, Duration, NumericHelpers, TimeStamp, U256}

class EmissionSpec extends AlephiumSpec with NumericHelpers {
  def equalLong(x: Long, y: Long, errorOrder: Long = 8): Assertion = {
    val errorBase = math.pow(10.0, errorOrder.toDouble).toLong
    (x >= y * (errorBase - 1) / errorBase && x <= y * (errorBase + 1) / errorBase) is true
  }

  def equalU256(x: U256, y: U256, errorOrder: Long = 8): Assertion = {
    val errorBase = math.pow(10.0, errorOrder.toDouble).toLong
    (x >= y * (errorBase - 1) / errorBase && x <= y * (errorBase + 1) / errorBase) is true
  }

  def equalBigInt(x: BigInteger, y: BigInteger, errorOrder: Long = 8): Assertion = {
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
    val emission = Emission(groupConfig, Duration.ofSecondsUnsafe(64))
  }

  it should "compute correct constants" in new Fixture {
    emission.blocksInAboutOneYearPerChain is 492750
    emission.durationToStableMaxReward is Duration.ofHoursUnsafe(4 * 365 * 24)

    val maxRewards = emission.blocksInAboutOneYearPerChain.mulUnsafe(Emission.initialMaxReward)
    val maxRate    = getInflationRate(maxRewards)
    (maxRate > 0.029 && maxRate < 0.03) is true

    val stableRewards = emission.blocksInAboutOneYearPerChain.mulUnsafe(Emission.stableMaxReward)
    val stableRate    = getInflationRate(stableRewards)
    (stableRate > 0.0098 && stableRate < 0.0099) is true
  }

  it should "compute max reward based on timestamp" in new Fixture {
    import emission._

    rewardWrtTime(TimeStamp.zero, TimeStamp.zero) is initialMaxRewardPerChain
    rewardWrtTime(TimeStamp.zero + Duration.ofHoursUnsafe(1 * 365 * 24), TimeStamp.zero) is
      ALF.cent(1250)
    rewardWrtTime(TimeStamp.zero + Duration.ofHoursUnsafe(2 * 365 * 24), TimeStamp.zero) is ALF
      .alf(10)
    rewardWrtTime(TimeStamp.zero + Duration.ofHoursUnsafe(3 * 365 * 24), TimeStamp.zero) is
      ALF.cent(750)
    rewardWrtTime(TimeStamp.zero + Duration.ofHoursUnsafe(4 * 365 * 24), TimeStamp.zero) is ALF
      .alf(5)
  }

  def halfDifficulty(target: Target): Target = {
    Target.unsafe(target.value.multiply(BigInteger.valueOf(2)))
  }

  def average(reward0: U256, reward1: U256): U256 = {
    reward0.addUnsafe(reward1).divUnsafe(U256.Two)
  }

  it should "compute reward based on target" in new Fixture {
    import emission._

    equalU256(rewardWrtTarget(Target.Max), lowHashRateInitialRewardPerChain)
    rewardWrtTarget(onePhPerSecondDivided) is initialMaxRewardPerChain
    rewardWrtTarget(oneEhPerSecondDivided) is stableMaxRewardPerChain
    rewardWrtTarget(a128EhPerSecondDivided) is U256.Zero
    rewardWrtTarget(Target.unsafe(BigInteger.ZERO)) is U256.Zero
    rewardWrtTarget(Target.unsafe(BigInteger.ONE)) is U256.Zero

    rewardWrtTarget(halfDifficulty(onePhPerSecondDivided)) is
      average(lowHashRateInitialRewardPerChain, initialMaxRewardPerChain)
    rewardWrtTarget(halfDifficulty(oneEhPerSecondDivided)) is
      average(initialMaxRewardPerChain, stableMaxRewardPerChain)
    rewardWrtTarget(halfDifficulty(a128EhPerSecondDivided)) is
      average(stableMaxRewardPerChain, U256.Zero)
  }

  it should "take the least reward" in new Fixture {
    import emission._

    equalU256(reward(Target.Max, TimeStamp.zero, TimeStamp.zero), lowHashRateInitialRewardPerChain)
    reward(
      onePhPerSecondDivided,
      TimeStamp.zero + Duration.ofHoursUnsafe(4 * 365 * 24),
      TimeStamp.zero
    ) is stableMaxRewardPerChain
  }

  behavior of "PoLW"

  it should "fail when hashrate is low" in new Fixture {
    import emission._

    assertThrows[AssertionError](burntAmountUnsafe(oneEhPerSecondDivided, U256.One))
    assertThrows[AssertionError](burntAmountUnsafe(onePhPerSecondDivided, U256.One))
    assertThrows[AssertionError](poLWTargetUnsafe(oneEhPerSecondDivided))
    assertThrows[AssertionError](poLWTargetUnsafe(onePhPerSecondDivided))
  }

  it should "calculate correct poLW target" in new Fixture {
    import emission._

    def check(target: Target): Assertion = {
      equalBigInt(
        poLWTargetUnsafe(target).value,
        target.value.multiply(BigInteger.valueOf(8)),
        1
      )
    }

    check(a128EhPerSecondDivided)
    check(Target.unsafe(a128EhPerSecondDivided.value.multiply(2)))
  }

  it should "calculate correct burnt amount" in new Fixture {
    import emission._

    def check(target: Target): Assertion = {
      equalU256(
        emission.burntAmountUnsafe(target, U256.Billion),
        U256.Billion * 3 / 4,
        1
      )
    }

    check(a128EhPerSecondDivided)
    check(Target.unsafe(a128EhPerSecondDivided.value.multiply(2)))
  }

  trait InflationFixture {
    val groupConfig: GroupConfig = new GroupConfig {
      override def groups: Int = 4
    }
    val blockTargetTime: Duration = Duration.ofSecondsUnsafe(64)
    val emission: Emission        = Emission(groupConfig, blockTargetTime)

    def check[T](rewards: IndexedSeq[(T, U256)], file: String, tToString: T => String): Unit = {
      Using(Source.fromResource(file)) { source =>
        val lines = source.getLines().toSeq
        lines.length is rewards.length
        lines.zipWithIndex.foreach { case (line, k) =>
          val row                   = line.split(",").map(_.filter(!_.isWhitespace))
          val expectedIndex         = row(0)
          val expectedYearlyReward  = row(2)
          val (index, yearlyReward) = rewards(k)
          tToString(index) is expectedIndex
          yearlyReward.v is (new BigInteger(expectedYearlyReward))
        }
      }.get
    }
  }

  it should "check the hashrate-based inflation" in new InflationFixture {
    val rewards = emission.rewardsWrtTarget()
    check[HashRate](rewards, "hashrate-inflation.csv", _.value.bitLength().toString)
  }

  it should "check the time-based inflation" in new InflationFixture {
    val rewards = emission.rewardsWrtTime()
    check[Int](rewards, "time-inflation.csv", _.toString)
  }
}
