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

import org.alephium.crypto._
import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.{AlephiumSpec, AVector}

class PublicKeyLikeSpec extends AlephiumSpec with GroupConfigFixture.Default {
  it should "serde correctly" in {
    val publicKey0 = PublicKeyLike.SecP256K1(SecP256K1PublicKey.generate)
    val bytes0     = ByteString(0) ++ publicKey0.rawBytes
    serialize[PublicKeyLike](publicKey0) is bytes0
    deserialize[PublicKeyLike](bytes0) isE publicKey0

    val publicKey1 = PublicKeyLike.SecP256R1(SecP256R1PublicKey.generate)
    val bytes1     = ByteString(1) ++ publicKey1.rawBytes
    serialize[PublicKeyLike](publicKey1) is bytes1
    deserialize[PublicKeyLike](bytes1) isE publicKey1

    val publicKey2 = PublicKeyLike.ED25519(ED25519PublicKey.generate)
    val bytes2     = ByteString(2) ++ publicKey2.rawBytes
    serialize[PublicKeyLike](publicKey2) is bytes2
    deserialize[PublicKeyLike](bytes2) isE publicKey2

    val publicKey3 = PublicKeyLike.Passkey(SecP256R1PublicKey.generate)
    val bytes3     = ByteString(3) ++ publicKey3.rawBytes
    serialize[PublicKeyLike](publicKey3) is bytes3
    deserialize[PublicKeyLike](bytes3) isE publicKey3

    val keys     = AVector[PublicKeyLike](publicKey0, publicKey1, publicKey2, publicKey3)
    val key      = keys.sample()
    val lastByte = key.rawBytes.last
    key.defaultGroup.value is ((lastByte & 0xff) % groupConfig.groups)

    deserialize[PublicKeyLike](ByteString(4)).leftValue.getMessage is "Invalid public key type 4"
  }
}
