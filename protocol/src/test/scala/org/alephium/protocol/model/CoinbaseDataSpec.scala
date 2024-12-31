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

import org.alephium.protocol.Generators
import org.alephium.protocol.config.{GroupConfigFixture, NetworkConfigFixture}
import org.alephium.serde.serialize
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

class CoinbaseDataSpec extends AlephiumSpec with Generators with GroupConfigFixture.Default {
  it should "serde CoinbaseDataV1" in {
    def test(prefix: CoinbaseDataPrefixV1, minerData: ByteString) = {
      val data = CoinbaseDataV1(prefix, minerData)
      data.ghostUncleData.isEmpty is true
      CoinbaseData.deserialize(serialize[CoinbaseData](data), HardFork.Mainnet).rightValue is data
      CoinbaseData.deserialize(serialize[CoinbaseData](data), HardFork.Leman).rightValue is data
      CoinbaseData.deserialize(serialize[CoinbaseData](data), HardFork.Rhone).isLeft is true
    }

    val prefix = CoinbaseDataPrefixV1.from(chainIndexGen.sample.get, TimeStamp.now())
    test(prefix, ByteString.empty)

    @scala.annotation.tailrec
    def randomMinerData(maxSize: Int): Array[Byte] = {
      val bytes = Random.nextBytes(Random.nextInt(maxSize))
      // if the first byte is 0, then it will decode an empty AVector
      if (bytes.nonEmpty && bytes(0) == 0) randomMinerData(maxSize) else bytes
    }
    test(prefix, ByteString.fromArrayUnsafe(randomMinerData(40)))
  }

  trait GhostUncleFixture extends NoIndexModelGenerators {
    val chainIndex = chainIndexGen.sample.get
    val ghostUncleData =
      AVector.fill(2)(GhostUncleData(BlockHash.random, assetLockupGen(chainIndex.to).sample.get))
  }

  it should "serde CoinbaseDataV2" in new GhostUncleFixture {
    val prefix = CoinbaseDataPrefixV1.from(chainIndex, TimeStamp.now())
    val data0  = CoinbaseDataV2(prefix, ghostUncleData, ByteString.empty)
    CoinbaseData.deserialize(serialize[CoinbaseData](data0), HardFork.Rhone).rightValue is data0

    val data1 = CoinbaseDataV2(
      prefix,
      ghostUncleData,
      ByteString.fromArrayUnsafe(Random.nextBytes(40))
    )
    CoinbaseData.deserialize(serialize[CoinbaseData](data1), HardFork.Rhone).rightValue is data1
  }

  it should "serde CoinbaseDataV3" in new GhostUncleFixture {
    val prefix = CoinbaseDataPrefixV2.from(chainIndex)
    val data0  = CoinbaseDataV3(prefix, ghostUncleData, ByteString.empty)
    CoinbaseData.deserialize(serialize[CoinbaseData](data0), HardFork.Danube).rightValue is data0

    val data1 = CoinbaseDataV3(
      prefix,
      ghostUncleData,
      ByteString.fromArrayUnsafe(Random.nextBytes(40))
    )
    CoinbaseData.deserialize(serialize[CoinbaseData](data1), HardFork.Danube).rightValue is data1
  }

  it should "get correct coinbase data" in new NoIndexModelGenerators {
    val chainIndex = chainIndexGen.sample.get
    val timestamp  = TimeStamp.now()
    val selectedGhostUncles =
      AVector(SelectedGhostUncle(BlockHash.generate, assetLockupGen(chainIndex.from).sample.get, 1))
    val minerData = ByteString.empty

    {
      implicit val networkConfig = NetworkConfigFixture.Genesis
      CoinbaseData.from(chainIndex, timestamp, selectedGhostUncles, minerData) is a[CoinbaseDataV1]
    }

    {
      implicit val networkConfig = NetworkConfigFixture.Leman
      CoinbaseData.from(chainIndex, timestamp, selectedGhostUncles, minerData) is a[CoinbaseDataV1]
    }

    {
      implicit val networkConfig = NetworkConfigFixture.Rhone
      CoinbaseData.from(chainIndex, timestamp, selectedGhostUncles, minerData) is a[CoinbaseDataV2]
    }

    {
      implicit val networkConfig = NetworkConfigFixture.Danube
      CoinbaseData.from(chainIndex, timestamp, selectedGhostUncles, minerData) is a[CoinbaseDataV3]
    }
  }
}
