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

import org.alephium.crypto.{SecP256K1PublicKey, SecP256R1PublicKey}
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.AlephiumSpec

class PublicKeyLikeSpec extends AlephiumSpec {
  it should "serde correctly" in {
    val publicKey0 = PublicKeyLike.SecP256K1(SecP256K1PublicKey.generate)
    val bytes0     = ByteString(0) ++ publicKey0.bytes
    serialize[PublicKeyLike](publicKey0) is bytes0
    deserialize[PublicKeyLike](bytes0) isE publicKey0

    val publicKey1 = PublicKeyLike.Passkey(SecP256R1PublicKey.generate)
    val bytes1     = ByteString(1) ++ publicKey1.bytes
    serialize[PublicKeyLike](publicKey1) is bytes1
    deserialize[PublicKeyLike](bytes1) isE publicKey1

    deserialize[PublicKeyLike](ByteString(2)).leftValue.getMessage is "Invalid public key type 2"
  }
}
