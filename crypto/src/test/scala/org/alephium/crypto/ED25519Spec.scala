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

import org.alephium.util.{AlephiumSpec, AVector}

class ED25519Spec extends AlephiumSpec {
  "ED25519" should "sign correctly" in {
    forAll { _message: IndexedSeq[Byte] =>
      val message  = AVector.from(_message)
      val (sk, pk) = ED25519.secureGeneratePriPub()
      val sign     = ED25519.sign(message, sk)
      ED25519.verify(message, sign, pk) is true
    }
  }

  it should "be verified with proper public key" in {
    forAll { (_message1: AVector[Byte], _message2: AVector[Byte]) =>
      whenever(_message1 != _message2) {
        val message1 = Blake2b.hash(_message1).bytes
        val message2 = Blake2b.hash(_message2).bytes

        val (sk1, pk1) = ED25519.secureGeneratePriPub()
        val (_, pk2)   = ED25519.secureGeneratePriPub()
        val signature  = ED25519.sign(message1, sk1)

        ED25519.verify(message1, signature, pk1) is true
        ED25519.verify(message2, signature, pk1) is false
        ED25519.verify(message1, signature, pk2) is false
      }
    }
  }
}
