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

import scala.util.Random

import akka.util.ByteString
import org.scalacheck.Gen

import org.alephium.protocol.Generators
import org.alephium.protocol.config.{GroupConfigFixture, NetworkConfigFixture}
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

class CoinbaseDataSpec extends AlephiumSpec with Generators with GroupConfigFixture.Default {
  it should "serde CoinbaseDataV1" in {
    def test(prefix: CoinbaseDataPrefix, minerData: ByteString) = {
      val data = CoinbaseDataV1(prefix, minerData)

      {
        implicit val networkConfig = NetworkConfigFixture.Genesis
        deserialize[CoinbaseData](serialize[CoinbaseData](data)).rightValue is data
      }
      {
        implicit val networkConfig = NetworkConfigFixture.Leman
        deserialize[CoinbaseData](serialize[CoinbaseData](data)).rightValue is data
      }
      {
        implicit val networkConfig = NetworkConfigFixture.Ghost
        deserialize[CoinbaseData](serialize[CoinbaseData](data)).isLeft is true
      }
    }

    val prefix = CoinbaseDataPrefix.from(chainIndexGen.sample.get, TimeStamp.now())
    test(prefix, ByteString.empty)
    test(prefix, ByteString.fromArrayUnsafe(Random.nextBytes(40)))
  }

  it should "serde CoinbaseDataV2" in {
    val prefix = CoinbaseDataPrefix.from(chainIndexGen.sample.get, TimeStamp.now())
    val hashes = AVector.from(Gen.listOfN(2, blockHashGen).sample.get)

    implicit val networkConfig = NetworkConfigFixture.Ghost
    val data0                  = CoinbaseDataV2(prefix, hashes, ByteString.empty)
    deserialize[CoinbaseData](serialize[CoinbaseData](data0)).rightValue is data0

    val data1 = CoinbaseDataV2(
      prefix,
      hashes,
      ByteString.fromArrayUnsafe(Random.nextBytes(40))
    )
    deserialize[CoinbaseData](serialize[CoinbaseData](data1)).rightValue is data1
  }
}
