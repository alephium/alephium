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

import org.alephium.protocol.PublicKey
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AlephiumSpec, Hex}

class AddressSpec extends AlephiumSpec {
  def succes(address: String, publicKey: String, networkType: NetworkType): Assertion = {
    val script = LockupScript.p2pkh(PublicKey.unsafe(Hex.from(publicKey).get))
    Address(networkType, script).toBase58 is address
    Address.fromBase58(address, networkType).get.lockupScript is script
  }

  def fail(address: String, networkType: NetworkType) = {
    NetworkType.all.filter(_ != networkType).foreach { differentNetworkType =>
      Address.fromBase58(address, differentNetworkType) is None
    }

    Address.fromBase58(scala.util.Random.nextString(address.length), networkType) is None
  }

  def tests(networkType: NetworkType, data: Seq[(String, String)]) = {
    data.foreach { case (address, publicKey) => succes(address, publicKey, networkType) }
    data.foreach { case (address, _)         => fail(address, networkType) }
  }

  it should "encode and decode p2pkh address to and from Base58" in {
    tests(
      NetworkType.Mainnet,
      Seq(
        ("M1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3",
         "02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10"),
        ("M1H7CmpbvGJwgyLzR91wzSJJSkiBC92WDPTWny4gmhQJQc",
         "03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19"),
        ("M1DkrQMni2h8KYpvY8t7dECshL66gwnxiR5uD2Udxps6og",
         "03c0a849d8ab8633b45b45ea7f3bb3229e1083a13fd73e027aac2bc55e7f622172"),
        ("M131R8ufDhcsu6SRztR9D3m8GUzkWFUPfT78aQ6jgtgzob",
         "026a1552ddf754abbfed6784f709fc94b7fe96049939986ea31e46238849953d18")
      )
    )

    tests(
      NetworkType.Testnet,
      Seq(
        ("T1J9XcQ5FsFfihNYMzdYKXoiZBTzsHQifzu7CKQfZPbwt1",
         "02f363a2a97f4f62e387c2ef2d8e2d9e9259f2724383c2ad7a7d156ea813b7faf3"),
        ("T16Q9sJkSYW66HKeai8sJeEo2buKLdwnmvY7VXtZFVDCoT",
         "030ba090e36fd7acee3234340d758f849856ee1ebc2008ca1e88e00f9c54f69a4d"),
        ("T15phYy54YWvsLbnUcn9xQAp82PgKXWRKfFUmDUYC13Ecm",
         "02f0a5d1a237f42f0038387cc6ee75b6d8fac424babc92f74a79c0d91c03d3a7ea"),
        ("T17ad4SSso1f3trkUfmi1YHkNnEo7qnF6SA83tdNJD2Saa",
         "03b466208c07ecac86c34ecfe41e93fab5656828231e76b63a2a078a3341d4b825")
      )
    )

    tests(
      NetworkType.Devnet,
      Seq(
        ("D15eh7Qe3CC9YgQcY3bpfZ9z6mSsU1mDuKKY4ox5ZNx51E",
         "0240a9ec37c5ad511577a61fe9ed0d1cf3cea9ae4d9ecb1ec851f7bad71f535aef"),
        ("D14GrSzgcEcJVRCjA8WE6VPaagr4csBvbuQUfpaCfDJnnF",
         "03274810507af37bf1ba31fa46114e0136862dd5beda0cfd76fda8a0b4d8f3d89e"),
        ("D13zsJa9zidziyzjxkwgsVePxBbgb1hBUCdYyoRRHR8epm",
         "034ac7cefe4f3c4b06a874da597791152f35ee2e36e082350a9d387735f6abe5bd"),
        ("D1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n",
         "0219d4766022b260f8b662824d510ded34594a5cc479ff21f676cc8968f988e886")
      )
    )
  }
}
