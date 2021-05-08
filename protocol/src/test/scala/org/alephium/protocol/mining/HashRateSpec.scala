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

import org.alephium.protocol.model.Target
import org.alephium.util.{AlephiumSpec, Duration}

class HashRateSpec extends AlephiumSpec {
  it should "check special values" in {
    HashRate.onePhPerSecond.value is BigInteger.valueOf(1024).pow(5)
    HashRate.oneEhPerSecond.value is BigInteger.valueOf(1024).pow(6)
    HashRate.a128EhPerSecond.value is
      BigInteger.valueOf(1024).pow(6).multiply(BigInteger.valueOf(128))
  }

  it should "convert from target correctly" in {
    (1 until 255).foreach { k =>
      val target   = Target.unsafe(BigInteger.ONE.shiftLeft(k))
      val hashrate = HashRate.from(target, Duration.ofSecondsUnsafe(1))
      Target.from(hashrate, Duration.ofSecondsUnsafe(1)) is target
    }
  }
}
