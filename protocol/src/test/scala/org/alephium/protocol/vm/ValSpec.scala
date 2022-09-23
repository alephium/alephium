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

package org.alephium.protocol.vm

import akka.util.ByteString

import org.alephium.protocol.model.Address
import org.alephium.util.{AlephiumSpec, I256, U256}

class ValSpec extends AlephiumSpec {
  it should "convert to debug messages" in {
    Val.True.toDebugString() is ByteString.fromString("true")
    Val.False.toDebugString() is ByteString.fromString("false")
    Val.I256(I256.from(-123456)).toDebugString() is ByteString.fromString("-123456")
    Val.U256(U256.unsafe(123456)).toDebugString() is ByteString.fromString("123456")
    Val.ByteVec(ByteString.fromString("Hello")).toDebugString() is ByteString.fromString("Hello")
    val addressString = "1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3"
    Val
      .Address(Address.fromBase58(addressString).value.lockupScript)
      .toDebugString() is ByteString.fromString(addressString)
  }
}
