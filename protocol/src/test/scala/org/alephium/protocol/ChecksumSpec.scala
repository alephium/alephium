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

package org.alephium.protocol

import org.alephium.util.{AlephiumSpec, Bytes, DjbHash}

class ChecksumSpec extends AlephiumSpec with Generators {
  it should "calculate checksum" in {
    forAll(hashGen) { hash =>
      val checksum = Checksum.calc(hash.bytes)
      checksum.bytes is Bytes.from(DjbHash.intHash(hash.bytes))
      checksum.check(hash.bytes) isE ()
      checksum.check(Hash.generate.bytes).isLeft is true
      Checksum.calcAndSerialize(hash.bytes) is checksum.bytes
    }
  }

  it should "serde checksum" in {
    forAll(bytesGen(4)) { bytes =>
      val checksum = Checksum.unsafe(bytes)
      Checksum.serde.serialize(checksum) is bytes
      Checksum.serde.deserialize(bytes) isE checksum
    }
  }
}
