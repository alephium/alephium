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

import org.alephium.crypto.Blake3
import org.alephium.protocol.Hash
import org.alephium.util.AlephiumSpec

class IdsSpec extends AlephiumSpec {
  it should "check equality for general hashes" in {
    val hash0 = Hash.hash("hello")
    val hash1 = Hash.hash("hello")
    val hash2 = Hash.hash("world")

    def test[T](f: Hash => T) = {
      f(hash0) is f(hash1)
      f(hash0) isnot f(hash2)
    }

    test(identity)
    test(TransactionId.apply)
    test(ContractId.apply)
    test(TokenId.apply)
  }

  it should "check equality for blake hash" in {
    val hash0 = Blake3.hash("hello")
    val hash1 = Blake3.hash("hello")
    val hash2 = Blake3.hash("world")

    BlockHash(hash0) is BlockHash(hash1)
    BlockHash(hash0) isnot BlockHash(hash2)
  }
}
