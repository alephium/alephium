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

import org.scalatest.Assertion

import org.alephium.protocol.mining.HashRate
import org.alephium.util.{AlephiumSpec, Duration, Hex}

class TargetSpec extends AlephiumSpec {
  it should "check special values" in {
    Target.unsafe(BigInteger.ZERO).toHexString is "00000000"
    Target.unsafe(BigInteger.ONE).toHexString is "01010000"
    Target.unsafe(BigInteger.valueOf(0x010101)).toHexString is "03010101"
    Target.unsafe(BigInteger.valueOf(256).pow(0xf)).toHexString is "10010000"
    Target
      .unsafe(BigInteger.valueOf(256).pow(0xf).multiply(BigInteger.valueOf(0xffffff)))
      .toHexString is "12ffffff"
    Target.Max.toHexString is "20ffffff"

    Target.onePhPerBlock.value is Target.maxBigInt.divide(BigInteger.valueOf(1024).pow(5))
    Target.oneEhPerBlock.value is Target.maxBigInt.divide(BigInteger.valueOf(1024).pow(6))
    Target.a128EhPerBlock.value is Target.maxBigInt.divide(
      BigInteger.valueOf(1024).pow(6).multiply(BigInteger.valueOf(128))
    )
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

  it should "scale correctly according to block time" in {
    val target0 = Target.from(HashRate.onePhPerSecond, Duration.ofSecondsUnsafe(1))
    val target1 = Target.from(HashRate.onePhPerSecond, Duration.ofSecondsUnsafe(2))
    val target2 = Target.from(HashRate.onePhPerSecond, Duration.ofMillisUnsafe(500))
    target1.value is target0.value.divide(2)
    target2.value is target0.value.multiply(2)
  }
}
