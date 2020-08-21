package org.alephium.protocol.vm

import org.scalatest.Assertion

import org.alephium.crypto.ALFPublicKey
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, Base58, Hex}

class LockupScriptSpec extends AlephiumSpec {
  def test(address: String, publicKey: String): Assertion = {
    val script = LockupScript.p2pkh(ALFPublicKey.unsafe(Hex.from(publicKey).get))
    script.toBase58 is address
    deserialize[LockupScript](Base58.decode(address).get) isE script
  }

  it should "encode and decode p2pkh address to and from Base58" in {
    test("1BHhKnn8moe8GsELAUQZKBhTeDTbPBmLXTCANkZ6eAHdK",
         "e7599ec69d841b61fe316d6d5ea8702263ecaa8ac883e04edcc021dbd1c33776")
    test("1Er5PoAV2NAPWAuH1E3eNtVnWftDT7krb1zBuLKpSxGyz",
         "29b118a333ec8aa20257cf8670eaab74ef1e737b1c8e9977cc63090988e06992")
    test("1DWu6iQySTRzVX6Fra6taEGVN9L8XgNHemP9QXwwRnVJg",
         "e25fcbff5d5534077ff21d93b229a5f1218b70b995f8492fc360958f0643a9c5")
    test("1GsuUk3ZjxbKAVutz1jsRqXNGLYWcxAM9Ztkw9GGVEK7C",
         "7a1d3ef98e21e8dd3077b2a5e1f67c75e9fbe3e02ff1d9e4dfe34a05acf92ebe")
  }
}
