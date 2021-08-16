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
import org.alephium.util.{AlephiumSpec, Hex}

class AddressSpec extends AlephiumSpec {
  def succes(address: String, publicKey: String): Assertion = {
    val script = LockupScript.p2pkh(PublicKey.unsafe(Hex.from(publicKey).get))
    Address.from(script).toBase58 is address
    Address.fromBase58(address).get.lockupScript is script
  }

  def tests(data: Seq[(String, String)]) = {
    data.foreach { case (address, publicKey) => succes(address, publicKey) }
  }

  it should "encode and decode p2pkh address to and from Base58" in {
    tests(
      Seq(
        (
          "1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3",
          "02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10"
        ),
        (
          "1H7CmpbvGJwgyLzR91wzSJJSkiBC92WDPTWny4gmhQJQc",
          "03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19"
        ),
        (
          "1DkrQMni2h8KYpvY8t7dECshL66gwnxiR5uD2Udxps6og",
          "03c0a849d8ab8633b45b45ea7f3bb3229e1083a13fd73e027aac2bc55e7f622172"
        ),
        (
          "131R8ufDhcsu6SRztR9D3m8GUzkWFUPfT78aQ6jgtgzob",
          "026a1552ddf754abbfed6784f709fc94b7fe96049939986ea31e46238849953d18"
        )
      )
    )
  }

  it should "parse asset address only" in {
    val lock    = LockupScript.P2C(Hash.random)
    val address = Address.from(lock).toBase58
    Address.asset(address) is None
    Address.fromBase58(address).get.lockupScript is lock
  }
}
