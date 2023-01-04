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

package org.alephium.protocol.model

import java.math.BigInteger

import scala.math.BigInt.javaBigInteger2bigInt

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.protocol.config.{GroupConfig, GroupConfigFixture}
import org.alephium.protocol.mining.HashRate
import org.alephium.util.{AlephiumSpec, AVector, Duration, Hex}

class TargetSpec extends AlephiumSpec with GroupConfigFixture {
  val groups: Int = 1

  it should "check special values" in {
    Target.unsafe(BigInteger.ZERO).toHexString is "00000000"
    Target.unsafe(BigInteger.ONE).toHexString is "01010000"
    Target.unsafe(BigInteger.valueOf(0x010101)).toHexString is "03010101"
    Target.unsafe(BigInteger.valueOf(256).pow(0xf)).toHexString is "10010000"
    Target
      .unsafe(BigInteger.valueOf(256).pow(0xf).multiply(BigInteger.valueOf(0xffffff)))
      .toHexString is "12ffffff"
    Target.Max.toHexString is "20ffffff"

    def singleChainTarget(hashRate: HashRate): Target = {
      Target.from(hashRate, Duration.ofSecondsUnsafe(1))
    }

    val onePhPerBlock: Target  = singleChainTarget(HashRate.onePhPerSecond)
    val oneEhPerBlock: Target  = singleChainTarget(HashRate.oneEhPerSecond)
    val a128EhPerBlock: Target = singleChainTarget(HashRate.a128EhPerSecond)

    onePhPerBlock.value is Target.maxBigInt.divide(BigInteger.valueOf(1024).pow(5))
    oneEhPerBlock.value is Target.maxBigInt.divide(BigInteger.valueOf(1024).pow(6))
    a128EhPerBlock.value is Target.maxBigInt.divide(
      BigInteger.valueOf(1024).pow(6).multiply(BigInteger.valueOf(128))
    )

    val blockTime = Duration.ofSecondsUnsafe(64)
    Target.from(HashRate.onePhPerSecond, blockTime).value is Target.maxBigInt
      .divide(
        BigInteger.valueOf(1024).pow(5)
      )
      .divide(64)
    Target.from(HashRate.oneEhPerSecond, blockTime).value is Target.maxBigInt
      .divide(
        BigInteger.valueOf(1024).pow(6)
      )
      .divide(64)
    Target.from(HashRate.a128EhPerSecond, blockTime).value is Target.maxBigInt
      .divide(
        BigInteger.valueOf(1024).pow(6).multiply(BigInteger.valueOf(128))
      )
      .divide(64)
  }

  it should "consider the conversion rate from hash rate" in {
    val hashRate = HashRate.unsafe(BigInteger.valueOf(2 * (16 * 16) / 64))
    val target = Target.from(hashRate, Duration.ofSecondsUnsafe(64))(new GroupConfig {
      val groups: Int = 4
    })
    target is Target.unsafe(Target.maxBigInt / 2)
  }

  it should "convert between big integer and compact bits" in {
    def check(bits: String): Assertion = {
      checkGeneric(bits, bits)
    }

    def checkGeneric(bits: String, expected: String): Assertion = {
      val bytes = Hex.from(bits).get
      Target.unsafe(Target.unsafe(bytes).value).bits is Hex.from(expected).get
    }

    check("00000000")
    check("03123456")
    check("04123456")
    check("12345667")
    check("20FFFFFF")

    checkGeneric("00123456", "00000000")
    checkGeneric("01123456", "01120000")
    checkGeneric("02123456", "02123400")
  }

  it should "convert from hashrate correctly" in {
    (2 until 256).foreach { k =>
      val hashrate = HashRate.unsafe(BigInteger.ONE.shiftLeft(k))
      val target   = Target.from(hashrate, Duration.ofSecondsUnsafe(1))
      HashRate.from(target, Duration.ofSecondsUnsafe(1)) is hashrate
    }
  }

  it should "convert to difficulty" in {
    (1 until 256).foreach { k =>
      val target = Target.unsafe(BigInteger.ONE.shiftLeft(k))
      target.getDifficulty().value is Target.maxBigInt.divide(target.value)
      target.getDifficulty().getTarget() is target
    }

    info("Precision might be lost")
    val target = Target.unsafe(ByteString(1, -55, 24, -80))
    target.getDifficulty().getTarget() isnot target
  }

  it should "scale correctly according to block time" in {
    val target0 = Target.from(HashRate.onePhPerSecond, Duration.ofSecondsUnsafe(1))
    val target1 = Target.from(HashRate.onePhPerSecond, Duration.ofSecondsUnsafe(2))
    val target2 = Target.from(HashRate.onePhPerSecond, Duration.ofMillisUnsafe(500))
    target1.value is target0.value.divide(2)
    target2.value is target0.value.multiply(2)
  }

  it should "clip target" in {
    val target0 = Target.unsafe(Hex.unsafe("20FFFFFF"))
    val target1 = Target.unsafe(Hex.unsafe("207FFFFF"))
    val target2 = Target.unsafe(Hex.unsafe("207FFFFE"))
    val target3 = Target.unsafe(Hex.unsafe("20800000"))
    Target.clipByTwoTimes(target0, target1) is target1
    Target.clipByTwoTimes(target0, target2) is target1
    Target.clipByTwoTimes(target0, target3) is target3
    assertThrows[AssertionError](Target.clipByTwoTimes(target2, target1))
  }

  it should "average targets" in new GroupConfigFixture.Default {
    val target = Target.unsafe(Hex.unsafe("200FFFFF"))
    val value  = target.value
    Target.average(target, AVector.fill(2 * groups - 1)(target)) is target
    Target.average(Target.Zero, AVector.fill(2 * groups - 1)(target)) is
      Target.unsafe(target.value * (2 * groups - 1) / (4 * groups))
    Target.average(target, AVector.fill(2 * groups - 1)(Target.Zero)) is
      Target.unsafe(target.value * (2 * groups + 1) / (4 * groups))
    val target1 = Target.unsafe(value * (2 * groups + 1))
    Target.average(target1, AVector.fill(2 * groups - 1)(target)) is
      Target.unsafe(value * (2 * groups + 3) / 2)
    val target2 = Target.unsafe(value * (2 * groups + 1))
    Target.average(target, AVector.fill(2 * groups - 1)(target2)) is
      Target.unsafe(value * (2 * groups + 1) / 2)
  }
}
