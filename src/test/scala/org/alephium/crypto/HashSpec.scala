package org.alephium.crypto

import akka.util.ByteString
import org.scalatest.TryValues._
import org.alephium.AlephiumSuite
import org.alephium.util.Hex._
import org.alephium.serde._

class HashSpec extends AlephiumSuite {
  "Sha256" should "hash correctly" in {
    val output   = Sha256.hash("Hello World")
    val expected = hex"A591A6D40BF420404A011733CFB7B190D62C65BF0BCDA32B57B277D9AD9F146E"
    ByteString(output.digest) shouldBe expected
  }

  "Sha256" should "serde correctly" in {
    val input  = Sha256.hash("Hello World")
    val output = deserialize[Sha256](serialize(input)).success.value
    ByteString(output.digest) shouldBe ByteString(input.digest)
  }
}
