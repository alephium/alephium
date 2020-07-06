package org.alephium.protocol.vm

import org.scalatest.Assertion

import org.alephium.crypto.ED25519PublicKey
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, Base58, Hex}

class LockupScriptSpec extends AlephiumSpec {
  def test(address: String, publicKey: String): Assertion = {
    val script = LockupScript.p2pkh(ED25519PublicKey.unsafe(Hex.from(publicKey).get))
    script.toBase58 is address
    deserialize[LockupScript](Base58.decode(address).get) isE script
  }

  it should "encode and decode p2pkh address to and from Base58" in {
    test("1Dx7Y4RxkCvoYvhQpMnRZi2yJnSbUeoVZY5R1vSYWSk9p",
         "9b85fef33febd483e055b01af0671714b56c6f3b6f45612589c2d6ae5337fb6d")
    test("1Ca33aWTUJFyoGbn7VuQoPqQgX9nyZKdLg9qVMh7JkMmw",
         "61bfcd3752298738385a69911105509a380d111e32b20bf4775959840095f572")
    test("13eh6wZfCah2ARW7ZE7HTYC8TvVhTteqNwjrqTHUk1XhD",
         "a8b0bd9198dcedb03dc0dc27267fc6f97def5c4471e1137160daed4a44c279c3")
    test("1AFmfTe1S6TYsKLmuMxhUSDj8hCvBVoAuff1CQnysNx15",
         "42335fabcc60bdd1f279722805ee33448e2750a4a40e729d2ebcdfba9ce5e3de")
  }
}
