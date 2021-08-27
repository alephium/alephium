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

import org.scalatest.Assertion

import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AlephiumSpec, AVector, Hex}

class AddressSpec extends AlephiumSpec {

  case class AddressVerify(
      address: String,
      pubKeys: AVector[String] = AVector.empty,
      m: Option[Int] = None
  ) {
    def threshold(threshold: Int): AddressVerify = {
      copy(m = Some(threshold))
    }

    def publicKey(keys: String*): AddressVerify = {
      copy(pubKeys = AVector.from(keys))
    }

    def ok() = {
      m match {
        case Some(threshold) =>
          val keys   = pubKeys.map(key => PublicKey.unsafe(Hex.from(key).value))
          val script = LockupScript.p2mpkh(keys, threshold).value
          verifyScript(address, script)
        case None =>
          if (pubKeys.length != 1) {
            fail("Verify p2pkh, but the length of public keys is not 1")
          } else {
            val script = LockupScript.p2pkh(PublicKey.unsafe(Hex.from(pubKeys.head).value))
            verifyScript(address, script)
          }
      }
    }

    private def verifyScript(address: String, script: LockupScript): Assertion = {
      Address.from(script).toBase58 is address
      Address.fromBase58(address).value.lockupScript is script
    }
  }

  it should "encode and decode between p2pkh address and public key" in {
    AddressVerify("1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3")
      .publicKey("02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10")
      .ok()

    AddressVerify("1H7CmpbvGJwgyLzR91wzSJJSkiBC92WDPTWny4gmhQJQc")
      .publicKey("03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19")
      .ok()

    AddressVerify("1DkrQMni2h8KYpvY8t7dECshL66gwnxiR5uD2Udxps6og")
      .publicKey("03c0a849d8ab8633b45b45ea7f3bb3229e1083a13fd73e027aac2bc55e7f622172")
      .ok()

    AddressVerify("131R8ufDhcsu6SRztR9D3m8GUzkWFUPfT78aQ6jgtgzob")
      .publicKey("026a1552ddf754abbfed6784f709fc94b7fe96049939986ea31e46238849953d18")
      .ok()
  }

  it should "encode and decode between p2mpkh address and public keys" in {
    info("1-of-2 p2mpkh")
    AddressVerify(
      "2jjvDdgGjC6X9HHMCMHohVfvp1uf3LHQrAGWaufR17P7AFwtxodTxSktqKc2urNEtaoUCy5xXpBUwpZ8QM8Q3e5BYCx"
    )
      .threshold(1)
      .publicKey(
        "02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10",
        "03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19"
      )
      .ok()

    info("2-of-2 p2mpkh")
    AddressVerify(
      "2jjvDdgGjC6X9HHMCMHohVfvp1uf3LHQrAGWaufR17P7AFwtxodTxSktqKc2urNEtaoUCy5xXpBUwpZ8QM8Q3e5BYCy"
    )
      .threshold(2)
      .publicKey(
        "02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10",
        "03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19"
      )
      .ok()

    info("2-of-3 p2mpkh")
    AddressVerify(
      "X3RMnvb8h3RFrrbBraEouAWU9Ufu4s2WTXUQfLCvDtcmqCWRwkVLc69q2NnwYW2EMwg4QBN2UopkEmYLLLgHP9TQ38FK15RnhhEwguRyY6qCuAoRfyjHRnqYnTvfypPgD7w1ku"
    )
      .threshold(2)
      .publicKey(
        "02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10",
        "03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19",
        "026a1552ddf754abbfed6784f709fc94b7fe96049939986ea31e46238849953d18"
      )
      .ok()
  }

  "Address.asset" should "parse asset address only" in {
    val lock    = LockupScript.P2C(Hash.random)
    val address = Address.from(lock).toBase58
    Address.asset(address) is None
    Address.fromBase58(address).value.lockupScript is lock
  }
}
