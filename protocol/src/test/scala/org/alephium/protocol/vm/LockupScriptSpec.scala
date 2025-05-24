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
import org.alephium.protocol.{Checksum, Hash}
import org.alephium.protocol.model.{ContractId, GroupIndex, NoIndexModelGenerators, ScriptHint}
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector, Bytes, DjbHash, Hex}

class LockupScriptSpec extends AlephiumSpec with NoIndexModelGenerators {
  it should "serde correctly" in {
    forAll(groupIndexGen.flatMap(assetLockupGen)) { lock =>
      serialize[LockupScript](lock) is serialize[LockupScript.Asset](lock)
      deserialize[LockupScript](serialize[LockupScript](lock)) isE lock
      deserialize[LockupScript.Asset](serialize[LockupScript.Asset](lock)) isE lock
    }

    forAll(groupIndexGen.flatMap(p2cLockupGen)) { lock =>
      deserialize[LockupScript](serialize[LockupScript](lock)) isE lock
      deserialize[LockupScript.Asset](serialize[LockupScript](lock)).leftValue is
        a[SerdeError.Validation]
    }
  }

  it should "serialize the examples" in {
    val hash0 = Hash.random
    val hash1 = Hash.random

    val lock0 = LockupScript.p2pkh(hash0)
    serialize[LockupScript](lock0) is Hex.unsafe(s"00${hash0.toHexString}")

    val lock1 = LockupScript.P2MPKH.unsafe(AVector(hash0, hash1), 1)
    serialize[LockupScript](lock1) is Hex.unsafe(s"0102${hash0.toHexString}${hash1.toHexString}01")

    val lock2 = LockupScript.p2sh(hash0)
    serialize[LockupScript](lock2) is Hex.unsafe(s"02${hash0.toHexString}")

    val lock3 = LockupScript.p2c(ContractId.unsafe(hash0))
    serialize[LockupScript](lock3) is Hex.unsafe(s"03${hash0.toHexString}")
  }

  it should "validate p2mpkh" in {
    val hash0 = Hash.random
    val hash1 = Hash.random

    val lock0 = Hex.unsafe(s"0102${hash0.toHexString}${hash1.toHexString}00")
    deserialize[LockupScript](lock0).leftValue.getMessage
      .startsWith(s"Invalid m in m-of-n multisig") is true

    val lock1 = Hex.unsafe(s"0102${hash0.toHexString}${hash1.toHexString}03")
    deserialize[LockupScript](lock1).leftValue.getMessage
      .startsWith(s"Invalid m in m-of-n multisig") is true

    val lock2 = Hex.unsafe(s"0102${hash0.toHexString}${hash1.toHexString}01")
    deserialize[LockupScript](lock2).isRight is true

    val lock3 = Hex.unsafe(s"0102${hash0.toHexString}${hash1.toHexString}02")
    deserialize[LockupScript](lock3).isRight is true

    val lock4 = Hex.unsafe(s"010000")
    deserialize[LockupScript](lock4).leftValue.getMessage
      .startsWith(s"Invalid m in m-of-n multisig") is true
  }

  it should "validate p2pk" in {
    val lockupScript = p2pkLockupGen(GroupIndex.unsafe(1)).sample.get
    lockupScript.groupIndex is lockupScript.scriptHint.groupIndex
    val publicKeyBytes = serialize(lockupScript.publicKey)
    val checksum       = Checksum.calcAndSerialize(publicKeyBytes)
    val groupByte      = ByteString(lockupScript.groupIndex.value.toByte)
    val bytes =
      Hex.unsafe(s"04${Hex.toHexString(publicKeyBytes ++ checksum ++ groupByte)}")
    serialize[LockupScript](lockupScript) is bytes
    deserialize[LockupScript](bytes) isE lockupScript

    (0 until groupConfig.groups).foreach { value =>
      val groupIndex = GroupIndex.unsafe(value)
      val p2pk       = LockupScript.p2pk(lockupScript.publicKey, groupIndex)
      p2pk.groupIndex is groupIndex
    }

    val invalidChecksum = bytesGen(Checksum.checksumLength).sample.get
    val invalidBytes =
      Hex.unsafe(s"04${Hex.toHexString(publicKeyBytes ++ invalidChecksum ++ groupByte)}")
    deserialize[LockupScript](invalidBytes).leftValue.getMessage
      .startsWith("Wrong checksum") is true
  }

  it should "validate p2hmpk" in {
    val lockupScript = p2hmpkLockupGen(GroupIndex.unsafe(2)).sample.get
    lockupScript.groupIndex is lockupScript.scriptHint.groupIndex
    LockupScript.P2HMPK.defaultGroup(lockupScript.p2hmpkHash) is
      GroupIndex.unsafe(Bytes.toPosInt(lockupScript.p2hmpkHash.bytes.last) % groups)

    val bytes = Hex.unsafe(s"05${lockupScript.p2hmpkHash.toHexString}02")
    serialize[LockupScript](lockupScript) is bytes
    deserialize[LockupScript](bytes) isE lockupScript

    (0 until groupConfig.groups).foreach { value =>
      val groupIndex = GroupIndex.unsafe(value)
      val p2hmpk     = LockupScript.p2hmpk(lockupScript.p2hmpkHash, groupIndex)
      p2hmpk.scriptHint.groupIndex is groupIndex
      p2hmpk.groupIndex is groupIndex
    }
  }

  it should "calculate correct script hint for p2pk address" in {
    forAll(groupIndexGen) { groupIndex =>
      forAll(p2pkLockupGen(groupIndex)) { lockupScript0 =>
        val publicKey = lockupScript0.publicKey
        lockupScript0.groupIndex is groupIndex
        lockupScript0.scriptHint.groupIndex is groupIndex
        lockupScript0.scriptHint.groupIndex.value.toByte is lockupScript0.groupByte

        val defaultGroupIndex = publicKey.defaultGroup
        val lockupScript1     = LockupScript.p2pk(publicKey, defaultGroupIndex)
        lockupScript1.scriptHint.groupIndex is defaultGroupIndex
        lockupScript1.scriptHint.groupIndex.value.toByte is lockupScript1.groupByte
      }
    }
  }

  it should "calculate correct script hint for p2hmpk address" in {
    forAll(groupIndexGen) { groupIndex =>
      forAll(p2hmpkLockupGen(groupIndex)) { lockupScript =>
        lockupScript.groupIndex is groupIndex
        lockupScript.scriptHint.groupIndex is groupIndex
        lockupScript.scriptHint.groupIndex.value.toByte is lockupScript.groupByte
      }
    }
  }

  it should "only modify the MSB of the public key's script hint" in {
    val publicKey   = PublicKeyLike.SecP256K1(SecP256K1PublicKey.generate)
    val initialHint = ScriptHint.fromHash(DjbHash.intHash(publicKey.rawBytes))
    (0 until groupConfig.groups).foreach { groupIndex =>
      val lockupScript = LockupScript.p2pk(publicKey, GroupIndex.unsafe(groupIndex))
      lockupScript.scriptHint.groupIndex.value is groupIndex
      (lockupScript.scriptHint.value & 0x00ffffff) is (initialHint.value & 0x00ffffff)
    }
  }

  it should "only modify the MSB of p2hmpk hash's script hint" in {
    val p2hmpkHash  = Hash.random
    val initialHint = ScriptHint.fromHash(DjbHash.intHash(p2hmpkHash.bytes))
    (0 until groupConfig.groups).foreach { groupIndex =>
      val lockupScript = LockupScript.p2hmpk(p2hmpkHash, GroupIndex.unsafe(groupIndex))
      lockupScript.scriptHint.groupIndex.value is groupIndex
      (lockupScript.scriptHint.value & 0x00ffffff) is (initialHint.value & 0x00ffffff)
    }
  }

  it should "decode from base58 string" in {
    import LockupScript._
    decodeFromBase58("1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3") is a[ValidLockupScript]
    decodeFromBase58("je9CrJD444xMSGDA2yr1XMvugoHuTc6pfYEaPYrKLuYa") is a[ValidLockupScript]
    decodeFromBase58("22sTaM5xer7h81LzaGA2JiajRwHwECpAv9bBuFUH5rrnr") is a[ValidLockupScript]
    decodeFromBase58("3ccJ8aEBYKBPJKuk6b9yZ1W1oFDYPesa3qQeM8v9jhaJtbSaueJ3L") is a[HalfDecodedP2PK]
    decodeFromBase58("3ccJ8aEBYKBPJKuk6b9yZ1W1oFDYPesa3qQeM8v9jhaJtbSaueJ3L:0") is
      a[ValidLockupScript]
    decodeFromBase58("2iMUVF9XEf7TkCK1gAvfv9HrG4B7qWSDa93p5Xa8D6A85:0") is a[ValidLockupScript]
    decodeFromBase58("2iMUVF9XEf7TkCK1gAvfv9HrG4B7qWSDa93p5Xa8D6A85") is a[HalfDecodedP2HMPK]
    decodeFromBase58(":1") is InvalidLockupScript
    decodeFromBase58("1C2:1") is InvalidLockupScript
    decodeFromBase58("1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3:0") is InvalidLockupScript
    decodeFromBase58("je9CrJD444xMSGDA2yr1XMvugoHuTc6pfYEaPYrKLuYa:0") is InvalidLockupScript
    decodeFromBase58("22sTaM5xer7h81LzaGA2JiajRwHwECpAv9bBuFUH5rrnr:0") is InvalidLockupScript
    decodeFromBase58("3ccJ8aEBYKBPJKuk6b9yZ1W1oFDYPesa3qQeM8v9jhaJtbSaueJ3") is InvalidLockupScript
  }
}
