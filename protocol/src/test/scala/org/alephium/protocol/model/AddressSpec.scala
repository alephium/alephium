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

import scala.util.Random

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.crypto.BIP340SchnorrPublicKey
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.model.ContractId
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector, Base58, Hex}

class AddressSpec extends AlephiumSpec with NoIndexModelGenerators {

  it should "calculate group index" in {
    def testP2pkh(pubKey: String, expectedGroup: Int) = {
      val publicKey = PublicKey.unsafe(Hex.unsafe(pubKey))
      Address.p2pkh(publicKey).groupIndex.value is expectedGroup
    }

    testP2pkh("02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10", 2)
    testP2pkh("03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19", 0)
    testP2pkh("03c0a849d8ab8633b45b45ea7f3bb3229e1083a13fd73e027aac2bc55e7f622172", 1)
    testP2pkh("026a1552ddf754abbfed6784f709fc94b7fe96049939986ea31e46238849953d18", 0)

    def testContract(id: String) = {
      val contractId = ContractId.unsafe(Hash.unsafe(Hex.unsafe(id)))
      Address.contract(contractId).groupIndex.value is contractId.bytes.last.toInt
    }

    testContract("a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd00")
    testContract("c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be01")
    testContract("c0a849d8ab8633b45b45ea7f3bb3229e1083a13fd73e027aac2bc55e7f622102")
  }

  it should "encode and decode between p2pkh address and public key" in {
    AddressVerifyP2PKH("1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3")
      .publicKey("02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10")
      .success()

    AddressVerifyP2PKH("1H7CmpbvGJwgyLzR91wzSJJSkiBC92WDPTWny4gmhQJQc")
      .publicKey("03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19")
      .success()

    AddressVerifyP2PKH("1DkrQMni2h8KYpvY8t7dECshL66gwnxiR5uD2Udxps6og")
      .publicKey("03c0a849d8ab8633b45b45ea7f3bb3229e1083a13fd73e027aac2bc55e7f622172")
      .success()

    AddressVerifyP2PKH("131R8ufDhcsu6SRztR9D3m8GUzkWFUPfT78aQ6jgtgzob")
      .publicKey("026a1552ddf754abbfed6784f709fc94b7fe96049939986ea31e46238849953d18")
      .success()
  }

  it should "encode and decode between p2mpkh address and public keys" in {
    AddressVerifyP2MPKH(
      "2jjvDdgGjC6X9HHMCMHohVfvp1uf3LHQrAGWaufR17P7AFwtxodTxSktqKc2urNEtaoUCy5xXpBUwpZ8QM8Q3e5BYCx"
    )
      .threshold(1)
      .publicKeys(
        "02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10",
        "03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19"
      )
      .success()

    AddressVerifyP2MPKH(
      "2jjvDdgGjC6X9HHMCMHohVfvp1uf3LHQrAGWaufR17P7AFwtxodTxSktqKc2urNEtaoUCy5xXpBUwpZ8QM8Q3e5BYCy"
    )
      .threshold(2)
      .publicKeys(
        "02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10",
        "03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19"
      )
      .success()

    val twoOfThree = AddressVerifyP2MPKH(
      "X3RMnvb8h3RFrrbBraEouAWU9Ufu4s2WTXUQfLCvDtcmqCWRwkVLc69q2NnwYW2EMwg4QBN2UopkEmYLLLgHP9TQ38FK15RnhhEwguRyY6qCuAoRfyjHRnqYnTvfypPgD7w1ku"
    )
      .threshold(2)
      .publicKeys(
        "02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10",
        "03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19",
        "026a1552ddf754abbfed6784f709fc94b7fe96049939986ea31e46238849953d18"
      )
    twoOfThree.success()

    val zeroOfThree = twoOfThree.threshold(0)
    zeroOfThree
      .copy(address = twoOfThree.address.init :+ 's')
      .fail()
  }

  it should "encode and decode between p2c address and public key" in {
    import Hex._
    AddressVerifyP2C("22sTaM5xer7h81LzaGA2JiajRwHwECpAv9bBuFUH5rrnr")
      .contractId(hex"798e9e137aec7c2d59d9655b4ffa640f301f628bf7c365083bb255f6aa5f89ef")
      .success()

    AddressVerifyP2C("2AA91hkrsVv14QDZWgxMJXxDDKTRKzZMPyakCVUbZEGoS")
      .contractId(hex"e5d64f886664c58378d41fe3b8c29dd7975da59245a4a6bf92c3a47339a9a0a9")
      .success()
  }

  it should "encode and decode between p2sh address and public key" in {
    import Hex._
    AddressVerifyP2SH("je9CrJD444xMSGDA2yr1XMvugoHuTc6pfYEaPYrKLuYa")
      .scriptHash(hex"798e9e137aec7c2d59d9655b4ffa640f301f628bf7c365083bb255f6aa5f89ef")
      .success()

    AddressVerifyP2SH("rvpeCy7GhsGHq8n6TnB1LjQh4xn1FMHJVXnsdZAniKZA")
      .scriptHash(hex"e5d64f886664c58378d41fe3b8c29dd7975da59245a4a6bf92c3a47339a9a0a9")
      .success()
  }

  it should "test Schnorr address" in {
    val address = SchnorrAddress(BIP340SchnorrPublicKey.zero)

    val script1 = StatelessScript.unsafe(
      AVector(
        Method.testDefault(
          isPublic = true,
          argsLength = 0,
          localsLength = 0,
          returnLength = 0,
          instrs = AVector[Instr[StatelessContext]](
            TxId,
            BytesConst(Val.ByteVec(BIP340SchnorrPublicKey.zero.bytes)),
            GetSegregatedSignature,
            VerifyBIP340Schnorr
          )
        )
      )
    )
    serialize(script1) is address.scriptByteCode
  }

  "Address.asset" should "parse asset address only" in {
    val lock    = LockupScript.P2C(ContractId.random)
    val address = Address.from(lock).toBase58
    Address.asset(address) is None
    Address.fromBase58(address).value.lockupScript is lock
  }

  it should "encode and decode between p2pk address and public key" in {
    val publicKey = "00e10a76a87b3211ca2f05be47b9ef8d2c9acedf3dfa9ee0268bf3a42ea3e29af1e1"
    (0 until groupConfig.groups).foreach { index =>
      val address = s"3ccJ8aEBYKBPJKuk6b9yZ1W1oFDYPesa3qQeM8v9jhaJtbSaueJ3L:${index}"
      AddressVerifyP2PK(address).publicKey(publicKey).group(index).success()
    }
  }

  it should "decode address from string" in {
    Address.fromBase58("1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3").isDefined is true
    Address.fromBase58("je9CrJD444xMSGDA2yr1XMvugoHuTc6pfYEaPYrKLuYa").isDefined is true
    Address.fromBase58("22sTaM5xer7h81LzaGA2JiajRwHwECpAv9bBuFUH5rrnr").isDefined is true
    Address
      .fromBase58("3ccJ8aEBYKBPJKuk6b9yZ1W1oFDYPesa3qQeM8v9jhaJtbSaueJ3L")
      .isDefined is false
    Address
      .fromBase58("3ccJ8aEBYKBPJKuk6b9yZ1W1oFDYPesa3qQeM8v9jhaJtbSaueJ3L:0")
      .isDefined is true

    Address.fromBase58(":1").isDefined is false
    Address.fromBase58("1C2:1").isDefined is false
    Address.fromBase58("1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3:0").isDefined is false
    Address.fromBase58("je9CrJD444xMSGDA2yr1XMvugoHuTc6pfYEaPYrKLuYa:0").isDefined is false
    Address.fromBase58("22sTaM5xer7h81LzaGA2JiajRwHwECpAv9bBuFUH5rrnr:0").isDefined is false
    Address.fromBase58("3ccJ8aEBYKBPJKuk6b9yZ1W1oFDYPesa3qQeM8v9jhaJtbSaueJ3").isDefined is false
    Address
      .fromBase58(
        s"3ccJ8aEBYKBPJKuk6b9yZ1W1oFDYPesa3qQeM8v9jhaJtbSaueJ3L:${groupConfig.groups + 1}"
      )
      .isDefined is true
  }

  sealed trait AddressVerify {
    val address: String
    def script: LockupScript

    def success(): Assertion = {
      Address.from(script).toBase58 is address
      Address.fromBase58(address).value.lockupScript is script
    }

    def fail(): Assertion = {
      Address.from(script).toBase58 is address
      Address.fromBase58(address) is None
    }
  }

  case class AddressVerifyP2PK(
      address: String,
      pubKey: Option[PublicKeyLike] = None,
      groupIndex: Option[GroupIndex] = None
  ) extends AddressVerify {
    def publicKey(key: String) = {
      copy(pubKey = Some(deserialize[PublicKeyLike](Hex.unsafe(key)).rightValue))
    }

    def group(index: Int) = copy(
      groupIndex = Some(GroupIndex.unsafe(index))
    )

    override def success(): Assertion = {
      groupIndex.foreach(g => script.groupIndex is g)
      val bytes = serialize[LockupScript](script)
      Base58.encode(bytes) isnot address
      Address.from(script).toBase58 is address
      Address.fromBase58(address) is Some(Address.from(script))
      Address.fromBase58(Base58.encode(bytes ++ bytesGen(Random.between(0, 5)).sample.get)) is None

      val addressWithoutGroup = script.toBase58WithoutGroup
      Address.fromBase58(s"$addressWithoutGroup:${script.groupIndex.value}") is Some(
        Address.Asset(script)
      )
      Address.fromBase58(Base58.encode(bytes ++ bytesGen(Random.between(1, 5)).sample.get)) is None
    }

    def script: LockupScript.P2PK = LockupScript.p2pk(pubKey.get, groupIndex.get)
  }

  case class AddressVerifyP2PKH(
      address: String,
      pubKey: Option[String] = None
  ) extends AddressVerify {
    def publicKey(key: String) = {
      copy(pubKey = Some(key))
    }

    def script: LockupScript = {
      LockupScript.p2pkh(PublicKey.unsafe(Hex.from(pubKey.value).value))
    }
  }

  case class AddressVerifyP2MPKH(
      address: String,
      pubKeys: AVector[String] = AVector.empty,
      m: Option[Int] = None
  ) extends AddressVerify {
    def threshold(threshold: Int) = {
      copy(m = Some(threshold))
    }

    def publicKeys(keys: String*) = {
      copy(pubKeys = AVector.from(keys))
    }

    def script: LockupScript = {
      val keys = pubKeys.map(key => PublicKey.unsafe(Hex.from(key).value))
      LockupScript.p2mpkhUnsafe(keys, m.value)
    }
  }

  case class AddressVerifyP2C(
      address: String,
      contractId: Option[ByteString] = None
  ) extends AddressVerify {
    def contractId(id: ByteString) = {
      copy(contractId = Some(id))
    }

    def script: LockupScript = {
      LockupScript.p2c(ContractId.from(contractId.value).value)
    }
  }

  case class AddressVerifyP2SH(
      address: String,
      scriptHash: Option[ByteString] = None
  ) extends AddressVerify {
    def scriptHash(hash: ByteString) = {
      copy(scriptHash = Some(hash))
    }

    def script: LockupScript = {
      LockupScript.p2sh(Hash.from(scriptHash.value).value)
    }
  }
}
