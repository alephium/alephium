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

package org.alephium

import akka.util.ByteString

import org.alephium.crypto.*
import org.alephium.util.Bytes

package object protocol {
  type Hash = Blake2b
  val Hash: Blake2b.type = Blake2b

  type BlockHash = Blake3
  val BlockHash: Blake3.type = Blake3

  type PublicKey = SecP256K1PublicKey
  val PublicKey: SecP256K1PublicKey.type = SecP256K1PublicKey

  type PrivateKey = SecP256K1PrivateKey
  val PrivateKey: SecP256K1PrivateKey.type = SecP256K1PrivateKey

  type Signature = SecP256K1Signature
  val Signature: SecP256K1Signature.type = SecP256K1Signature

  val SignatureSchema: SecP256K1.type = SecP256K1

  // scalastyle:off magic.number
  val CurrentWireVersion: WireVersion =
    WireVersion(Bytes.toIntUnsafe(ByteString(0, 0, 11, 0)))
  val CurrentDiscoveryVersion: DiscoveryVersion =
    DiscoveryVersion(Bytes.toIntUnsafe(ByteString(0, 0, 0, 0)))
  // scalastyle:on
}
