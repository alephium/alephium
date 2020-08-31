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
    test("164RM7TNkCW962cDTYY8R1rNmeaVNqXYNj23QxZ5SjCxp",
         "024e7d22d0421f69e3adfb3ce888d17b8a65935b6950a19fc3ce09411f3b03b4f1")
    test("19R6J74NfsjVYRhUcKJUkQY23BE16y7kKmRenbweeeQZE",
         "02121785bea5026e4519317be483f93d2095230095d8e2307b6998c1e274986bc8")
    test("19nCSupu7HhghqDYXiN13oFmt7bTNKNVWBDhnLHCAgXkt",
         "02d717f8bc8b64c724a970e70afcd3b4a16e52be08ddbfaa2e728791f4bf67612c")
    test("14MiCiri3fr1wXFPauzQxDSYNdKEgmgLXrPw6hj5T3CWL",
         "02b8526747cb466870d688cee1e27903aed58825adb3e1f36bba7ed458479a63b8")
  }
}
