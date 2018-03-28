package org.alephium.crypto

import org.scalatest.TryValues._
import org.alephium.AlephiumSuite
import org.alephium.util.Hex._
import org.alephium.serde._

class HashSpec extends AlephiumSuite {
  "Sha256" should "hash correctly" in {
    val output   = Sha256.hash("Hello World")
    val expected = hex"A591A6D40BF420404A011733CFB7B190D62C65BF0BCDA32B57B277D9AD9F146E"
    output.digest shouldBe expected
  }

  it should "serde correctly" in {
    val input  = Sha256.hash("Hello World")
    val output = deserialize[Sha256](serialize(input)).success.value
    output shouldBe input
  }
}
