package org.alephium.crypto

import akka.util.ByteString
import org.alephium.AlephiumSuite
import org.alephium.util.Hex._

class HashSpec extends AlephiumSuite {
  "Sha256" should "hash correctly" in {
    val input  = ByteString.fromString("Hello World").toArray
    val output = Sha256.hash(input)
    output.digest shouldBe hex"A591A6D40BF420404A011733CFB7B190D62C65BF0BCDA32B57B277D9AD9F146E".toArray
  }
}
