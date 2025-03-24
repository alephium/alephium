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

package org.alephium.crypto

import org.alephium.serde
import org.alephium.util.AlephiumSpec

class Byte64Spec extends AlephiumSpec {
  it should "test Byte64" in {
    {
      info("from bytes")
      Byte64.from(bytesGen(63).sample.get) is None
      Byte64.from(bytesGen(65).sample.get) is None
      val bytes = bytesGen(64).sample.get
      Byte64.from(bytes).value.bytes is bytes
    }

    {
      info("from signature")
      val signature0 = SecP256K1Signature.generate
      Byte64.from(signature0).bytes is signature0.bytes
      val signature1 = SecP256R1Signature.generate
      Byte64.from(signature1).bytes is signature1.bytes
      val signature2 = ED25519Signature.generate
      Byte64.from(signature2).bytes is signature2.bytes
    }

    {
      info("serde")
      forAll(bytesGen(64)) { bytes =>
        val byte64 = Byte64.from(bytes).get
        serde.serialize(byte64) is bytes
        serde.deserialize[Byte64](bytes) isE byte64
      }
    }
  }
}
