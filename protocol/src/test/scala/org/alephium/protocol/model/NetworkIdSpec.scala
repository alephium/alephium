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

import org.scalacheck.Gen

import org.alephium.util.AlephiumSpec

class NetworkIdSpec extends AlephiumSpec {
  it should "return the right node folder" in {
    NetworkId.AlephiumMainNet.nodeFolder is "mainnet"
    NetworkId.AlephiumTestNet.nodeFolder is "testnet"
    NetworkId(2).nodeFolder is "network-2"
  }

  it should "correctly identify network types" in {
    NetworkId(0).networkType is NetworkId.MainNetType
    NetworkId(1).networkType is NetworkId.TestNetType
    NetworkId(2).networkType is NetworkId.TestNetType
  }

  it should "treat all non-canonical network IDs as testnet type" in {
    forAll(Gen.chooseNum[Byte](Byte.MinValue, Byte.MaxValue)) { id =>
      val networkId = NetworkId(id)
      if (id == 0) {
        networkId.networkType is NetworkId.MainNetType
      } else if (id == 1) {
        networkId.networkType is NetworkId.TestNetType
      } else {
        networkId.networkType is NetworkId.TestNetType
      }
    }
  }

  it should "generate correct node folders" in {
    forAll(Gen.chooseNum[Byte](Byte.MinValue, Byte.MaxValue)) { id =>
      val networkId = NetworkId(id)
      if (id == 0) {
        networkId.nodeFolder is "mainnet"
      } else if (id == 1) {
        networkId.nodeFolder is "testnet"
      } else {
        networkId.nodeFolder is s"network-$id"
      }
    }
  }

  it should "validate network ID" in {
    NetworkId.from(Byte.MinValue) is Some(NetworkId(Byte.MinValue))
    NetworkId.from(Byte.MaxValue) is Some(NetworkId(Byte.MaxValue))

    NetworkId.from(Byte.MinValue - 1) is None
    NetworkId.from(Byte.MaxValue + 1) is None
  }
}
