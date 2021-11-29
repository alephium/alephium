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

import org.alephium.protocol.ALPH
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
    (BigDecimal(amount.toBigInt) / BigDecimal(ALPH.MaxALPHValue.toBigInt)).doubleValue
  }

  implicit class RichTarget(target: Target) {
    def *(n: Int): Target = Target.unsafe(target.value * n)
    def /(n: Int): Target = Target.unsafe(target.value / n)
  }

  trait Fixture {
    val groupConfig = new GroupConfig {
      override def groups: Int = 4
    }
    val blockTime = Duration.ofSecondsUnsafe(64)
    val emission  = Emission(groupConfig, blockTime)
  }

  it should "compute correct constants" in new Fixture {
    emission.blocksInAboutOneYearPerChain is 492750
    emission.durationToStableMaxReward is Duration.ofHoursUnsafe(4 * 365 * 24)

    // Note: rank - 8 (chain num & chain index) + 6 (64 seconds)
    emission.onePhPerSecondTarget is Target.unsafe(Target.maxBigInt.shiftRight(50 - 8 + 6))
    emission.oneEhPerSecondTarget is Target.unsafe(Target.maxBigInt.shiftRight(60 - 8 + 6))
    emission.a128EhPerSecondTarget is Target.unsafe(Target.maxBigInt.shiftRight(67 - 8 + 6))

    emission.onePhPerSecondRank is 50
    emission.oneEhPerSecondRank is 60
    emission.a128EhPerSecondRank is 67

    val maxRewards = Emission.initialMaxReward * emission.blocksInAboutOneYearPerChain
    val maxRate    = getInflationRate(maxRewards)
    (maxRate > 0.029 && maxRate < 0.03) is true

    val stableRewards = Emission.stableMaxReward * emission.blocksInAboutOneYearPerChain
    val stableRate    = getInflationRate(stableRewards)
    (stableRate > 0.0098 && stableRate < 0.0099) is true
  }

  it should "compute max reward based on timestamp" in new Fixture {
    import emission._

    rewardWrtTime(TimeStamp.zero, TimeStamp.zero) is ALPH.cent(375)
    rewardWrtTime(TimeStamp.zero + Duration.ofHoursUnsafe(1 * 365 * 24), TimeStamp.zero) is
      ALPH.cent(313)
    rewardWrtTime(TimeStamp.zero + Duration.ofHoursUnsafe(2 * 365 * 24), TimeStamp.zero) is
      ALPH.cent(251)
    rewardWrtTime(TimeStamp.zero + Duration.ofHoursUnsafe(3 * 365 * 24), TimeStamp.zero) is
      ALPH.cent(189)
    rewardWrtTime(TimeStamp.zero + Duration.ofHoursUnsafe(4 * 365 * 24), TimeStamp.zero) is
      ALPH.cent(125)
  }

  def average(reward0: U256, reward1: U256): U256 = {
    reward0.addUnsafe(reward1).divUnsafe(U256.Two)
  }

  it should "compute reward based on target" in new Fixture {
    import emission._

    rewardWrtTarget(onePhPerSecondTarget) is initialMaxRewardPerChain
    rewardWrtTarget(oneEhPerSecondTarget) is stableMaxRewardPerChain
    rewardWrtTarget(a128EhPerSecondTarget) is U256.Zero
    rewardWrtTarget(Target.unsafe(BigInteger.ONE)) is U256.Zero
  }

  it should "take the least reward" in new Fixture {
    import emission._

    equalU256(
      reward(oneEhPerSecondTarget, TimeStamp.zero, TimeStamp.zero).miningReward,
      stableMaxRewardPerChain
    )
    reward(
      onePhPerSecondTarget,
      TimeStamp.zero + Duration.ofHoursUnsafe(4 * 365 * 24),
      TimeStamp.zero
    ).miningReward is stableMaxRewardPerChain
  }

  behavior of "PoLW"

  it should "fail when hashrate is low" in new Fixture {
    import emission._

    assertThrows[AssertionError](burntAmountUnsafe(oneEhPerSecondTarget, U256.One))
    assertThrows[AssertionError](burntAmountUnsafe(onePhPerSecondTarget, U256.One))
    assertThrows[AssertionError](polwTargetUnsafe(oneEhPerSecondTarget))
    assertThrows[AssertionError](polwTargetUnsafe(onePhPerSecondTarget))
  }

  it should "calculate correct poLW target" in new Fixture {
    import emission._

    // the polw hashrate should be ~8 time higher and the polw target should be ~8 times smaller
    def check(target: Target): Assertion = {
      equalBigInt(polwTargetUnsafe(target).value, target.value / 8, 1)
    }

    check(a128EhPerSecondTarget)
    check(a128EhPerSecondTarget * 2)
  }

  it should "calculate correct burnt amount" in new Fixture {
    import emission._

    def check(target: Target): Assertion = {
      equalU256(
        emission.burntAmountUnsafe(target, U256.Billion),
        U256.Billion * 7 / 8,
        1
      )
    }

    check(a128EhPerSecondTarget)
    check(a128EhPerSecondTarget * 2)
  }

  it should "calculate correct reward" in new Fixture {
    import emission._

    val now = TimeStamp.now()
    reward(Target.Max / 1024, now, now) is Emission.PoW(rewardWrtTarget(Target.Max / 1024))
    val polwReward = reward(oneEhPerSecondTarget / 2, now, now).asInstanceOf[Emission.PoLW]
    polwReward.miningReward is rewardWrtTarget(oneEhPerSecondTarget / 9)
    polwReward.burntAmount is burntAmountUnsafe(
      oneEhPerSecondTarget / 9,
      polwReward.miningReward
    )
  }

  behavior of "Inflation"

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
    check[HashRate](rewards, "hashrate-inflation.csv", hr => (hr.value.bitLength() - 1).toString)
  }

  it should "check the time-based inflation" in new InflationFixture {
    val rewards = emission.rewardsWrtTime()
    check[Int](rewards, "time-inflation.csv", _.toString)
  }
}
