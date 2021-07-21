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

import org.scalacheck.Gen

import org.alephium.protocol.PublicKey
import org.alephium.protocol.model.NoIndexModelGenerators
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector, Hex}

class UnlockScriptSpec extends AlephiumSpec with NoIndexModelGenerators {
  val keyGen = groupIndexGen.flatMap(publicKeyGen)

  it should "serde correctly" in {
    forAll(keyGen) { publicKey =>
      val unlock = UnlockScript.p2pkh(publicKey)
      deserialize[UnlockScript](serialize[UnlockScript](unlock)) isE unlock
    }

    forAll(keyGen, Gen.nonEmptyListOf(keyGen).map(AVector.from)) { (key0, moreKeys) =>
      val allKeys = (key0 +: moreKeys).mapWithIndex((_, _))
      val unlock  = UnlockScript.p2mpkh(allKeys)
      deserialize[UnlockScript](serialize[UnlockScript](unlock)) isE unlock
    }

    val unlock = UnlockScript.p2sh(StatelessScript(AVector.empty), AVector.empty)
    deserialize[UnlockScript](serialize[UnlockScript](unlock)) isE unlock
  }

  it should "serialize examples" in {
    val publicKey0 = PublicKey.generate
    val publicKey1 = PublicKey.generate

    val unlock0 = UnlockScript.p2pkh(publicKey0)
    serialize[UnlockScript](unlock0) is Hex.unsafe(s"00${publicKey0.toHexString}")

    val unlock1 = UnlockScript.p2mpkh(AVector(publicKey0 -> 1, publicKey1 -> 3))
    serialize[UnlockScript](unlock1) is
      Hex.unsafe(s"0102${publicKey0.toHexString}01${publicKey1.toHexString}03")

    val unlock2 = UnlockScript.p2sh(StatelessScript(AVector.empty), AVector.empty)
    serialize[UnlockScript](unlock2) is Hex.unsafe(s"020000")
  }
}
