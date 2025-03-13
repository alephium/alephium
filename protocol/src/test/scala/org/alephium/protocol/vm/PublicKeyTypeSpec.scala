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
import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.AlephiumSpec

class PublicKeyTypeSpec extends AlephiumSpec with GroupConfigFixture.Default {
  it should "serde correctly" in {
    val publicKey = PublicKeyType.SecP256K1(SecP256K1PublicKey.generate)
    val bytes     = ByteString(0) ++ publicKey.publicKey.bytes
    serialize[PublicKeyType](publicKey) is bytes
    deserialize[PublicKeyType](bytes) isE publicKey
    val lastByte = publicKey.publicKey.bytes.last
    publicKey.defaultGroup.value is ((lastByte & 0xff) % groupConfig.groups)

    deserialize[PublicKeyType](ByteString(1)).leftValue.getMessage is "Invalid public key type 1"
  }
}
