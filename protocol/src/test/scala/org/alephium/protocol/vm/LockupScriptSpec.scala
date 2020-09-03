package org.alephium.protocol.vm

import org.scalatest.Assertion

import org.alephium.protocol.PublicKey
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, Base58, Hex}

class LockupScriptSpec extends AlephiumSpec {
  def test(address: String, publicKey: String): Assertion = {
    val script = LockupScript.p2pkh(PublicKey.unsafe(Hex.from(publicKey).get))
    script.toBase58 is address
    deserialize[LockupScript](Base58.decode(address).get) isE script
  }

  it should "encode and decode p2pkh address to and from Base58" in {
    test("1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3",
         "02a16415ccabeb3bc1ee21daacdd53b780fb287afc1f9ab02ae21bb7559d84dd10")
    test("1H7CmpbvGJwgyLzR91wzSJJSkiBC92WDPTWny4gmhQJQc",
         "03c83325bd2c0fe1464161c6d5f42699fc9dd799dda7f984f9fbf59b01b095be19")
    test("1DkrQMni2h8KYpvY8t7dECshL66gwnxiR5uD2Udxps6og",
         "03c0a849d8ab8633b45b45ea7f3bb3229e1083a13fd73e027aac2bc55e7f622172")
    test("131R8ufDhcsu6SRztR9D3m8GUzkWFUPfT78aQ6jgtgzob",
         "026a1552ddf754abbfed6784f709fc94b7fe96049939986ea31e46238849953d18")
  }
}
