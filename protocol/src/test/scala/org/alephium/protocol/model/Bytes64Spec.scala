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

import org.alephium.protocol.Signature
import org.alephium.serde
import org.alephium.util.AlephiumSpec

class Bytes64Spec extends AlephiumSpec {
  it should "test Bytes64" in {
    {
      info("from bytes")
      Bytes64.from(bytesGen(63).sample.get) is None
      Bytes64.from(bytesGen(65).sample.get) is None
      val bytes = bytesGen(64).sample.get
      Bytes64.from(bytes).value.bytes is bytes
    }

    {
      info("from signature")
      val signature = Signature.generate
      Bytes64.from(signature).bytes is signature.bytes
    }

    {
      info("serde")
      forAll(bytesGen(64)) { bytes =>
        val bytes64 = Bytes64.from(bytes).get
        serde.serialize(bytes64) is bytes
        serde.deserialize[Bytes64](bytes) isE bytes64
      }
    }
  }
}
