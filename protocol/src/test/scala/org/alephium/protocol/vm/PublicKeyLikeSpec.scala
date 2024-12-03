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

import org.alephium.crypto.SecP256K1PublicKey
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.AlephiumSpec

class PublicKeyLikeSpec extends AlephiumSpec {
  it should "serde correctly" in {
    val publicKey = PublicKeyLike.SecP256K1(SecP256K1PublicKey.generate)
    val bytes     = ByteString(0) ++ publicKey.publicKey.bytes
    serialize[PublicKeyLike](publicKey) is bytes
    deserialize[PublicKeyLike](bytes) isE publicKey
    deserialize[PublicKeyLike](ByteString(1)).leftValue.getMessage is "Invalid public key type 1"
  }
}
