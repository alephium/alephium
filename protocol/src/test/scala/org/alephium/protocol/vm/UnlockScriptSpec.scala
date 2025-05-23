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

import org.alephium.crypto.{ED25519PublicKey, SecP256K1PublicKey, SecP256R1PublicKey}
import org.alephium.protocol.PublicKey
import org.alephium.protocol.model.NoIndexModelGenerators
import org.alephium.protocol.vm.LockupScript.Groupless
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector, Hex}

class UnlockScriptSpec extends AlephiumSpec with NoIndexModelGenerators {
  val keyGen = groupIndexGen.flatMap(publicKeyGen)
  val dummyMethod = Method.testDefault[StatelessContext](
    isPublic = true,
    argsLength = 0,
    localsLength = 0,
    returnLength = 0,
    instrs = AVector.empty
  )
  val dummyScript = StatelessScript.unsafe(AVector(dummyMethod))

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

    val unlock = UnlockScript.p2sh(dummyScript, AVector.empty)
    deserialize[UnlockScript](serialize[UnlockScript](unlock)) isE unlock

    deserialize[UnlockScript](
      serialize[UnlockScript](UnlockScript.SameAsPrevious)
    ) isE UnlockScript.SameAsPrevious

    forAll(keyGen) { publicKey =>
      val unlock = UnlockScript.polw(publicKey)
      deserialize[UnlockScript](serialize[UnlockScript](unlock)) isE unlock
    }

    deserialize[UnlockScript](serialize[UnlockScript](UnlockScript.P2PK)) isE UnlockScript.P2PK

    val pubKey0 = SecP256K1PublicKey.generate
    val pubKey1 = SecP256R1PublicKey.generate
    val pubKey2 = ED25519PublicKey.generate
    val pubKey3 = SecP256R1PublicKey.generate

    val publicKeys = AVector(
      PublicKeyLike.SecP256K1(pubKey0),
      PublicKeyLike.SecP256R1(pubKey1),
      PublicKeyLike.ED25519(pubKey2),
      PublicKeyLike.WebAuthn(pubKey3)
    )
    val publicKeyIndexes = AVector(0, 1, 2, 3)
    deserialize[UnlockScript](
      serialize[UnlockScript](UnlockScript.P2HMPK(publicKeys, publicKeyIndexes))
    ) isE UnlockScript.P2HMPK(publicKeys, publicKeyIndexes)
  }

  it should "serialize examples" in {
    val publicKey0 = PublicKey.generate
    val publicKey1 = PublicKey.generate

    val unlock0 = UnlockScript.p2pkh(publicKey0)
    serialize[UnlockScript](unlock0) is Hex.unsafe(s"00${publicKey0.toHexString}")

    val unlock1 = UnlockScript.p2mpkh(AVector(publicKey0 -> 1, publicKey1 -> 3))
    serialize[UnlockScript](unlock1) is
      Hex.unsafe(s"0102${publicKey0.toHexString}01${publicKey1.toHexString}03")

    val unlock2 = UnlockScript.p2sh(dummyScript, AVector.empty)
    serialize[UnlockScript](unlock2) is Hex.unsafe(s"020101000000000000")

    serialize[UnlockScript](UnlockScript.SameAsPrevious) is Hex.unsafe(s"03")

    val unlock3 = UnlockScript.polw(publicKey0)
    val encoded = Hex.unsafe(s"04${publicKey0.toHexString}")
    serialize[UnlockScript](unlock3) is encoded
    deserialize[UnlockScript](encoded).rightValue is unlock3

    serialize[UnlockScript](UnlockScript.P2PK) is Hex.unsafe("05")

    val publicKeys = AVector(
      PublicKeyLike.SecP256K1(SecP256K1PublicKey.generate),
      PublicKeyLike.SecP256R1(SecP256R1PublicKey.generate)
    )
    val publicKeyIndexes = AVector(0)
    val serializedPublicKey0 =
      Hex.toHexString(Groupless.safePublicKeySerde.serialize(publicKeys(0)))
    val serializedPublicKey1 =
      Hex.toHexString(Groupless.safePublicKeySerde.serialize(publicKeys(1)))
    serialize[UnlockScript](UnlockScript.P2HMPK(publicKeys, publicKeyIndexes)) is
      Hex.unsafe(s"0602${serializedPublicKey0}${serializedPublicKey1}0100")

    deserialize[UnlockScript](
      serialize[UnlockScript](UnlockScript.P2HMPK(publicKeys, AVector.empty))
    ).leftValue.getMessage() is "Public key indexes can not be empty"

    deserialize[UnlockScript](
      serialize[UnlockScript](UnlockScript.P2HMPK(publicKeys, AVector(1, 0)))
    ).leftValue
      .getMessage() is "Public key indexes should be sorted in ascending order, each index should be in range [0, publicKeys.length)"

    deserialize[UnlockScript](
      serialize[UnlockScript](UnlockScript.P2HMPK(publicKeys, AVector(1, 3)))
    ).leftValue
      .getMessage() is "Public key indexes should be sorted in ascending order, each index should be in range [0, publicKeys.length)"

    deserialize[UnlockScript](
      serialize[UnlockScript](UnlockScript.P2HMPK(publicKeys, AVector(0, 1, 2)))
    ).leftValue
      .getMessage() is "Public key indexes length can not be greater than public keys length"
  }

  it should "validate multisig" in {
    val publicKey0 = PublicKey.generate
    val publicKey1 = PublicKey.generate

    def test(message: String, expected: Boolean, keys: (PublicKey, Int)*) = {
      info(message)
      UnlockScript.validateP2mpkh(UnlockScript.p2mpkh(AVector.from(keys))) is expected
    }

    test("Key indexes are duplicated", false, (publicKey0, 1), (publicKey1, 1))
    test("Key indexes are decreasing", false, (publicKey0, 1), (publicKey1, 0))
    test("Positive case", true, (publicKey0, 0), (publicKey1, 3))
  }
}
