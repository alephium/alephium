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
    test("16xrcCkHtAcrgpcDkXSU2zvSWdRGtkjKzSaP1DxjcxKCe",
         "0269bd0589fd46f2303c1b2792b111c867d047b2b5b232916204121ea0115c6ea4")
    test("1AEXDEwHCX2sbSBWk77Zt63q3gQodLjFG34Dh8LEgHsHZ",
         "02146ea15828c5abf7d04322050d0e5a33f7ea3c3d022707874f55b011ec48439d")
    test("1HM49LXE2NTYynenLqmzT7EQG37FHWcziv9kDJ4cefg3L",
         "02b599c01fc6f23c94ab82482791edc9fef7adb92e69b021fef80cb7e4cc27f23e")
    test("1B5EXqwPU89Zju6YqX9eqAXxTvNyp6xdY6VjXAhhQPytp",
         "03b9fea617c3005fc5decfef5bb3d9ccf68108acbbadb91996619fdcc9520c356a")
  }
}
