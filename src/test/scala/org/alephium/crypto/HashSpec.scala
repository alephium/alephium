package org.alephium.crypto

import org.scalatest.TryValues._
import org.alephium.AlephiumSuite
import org.alephium.util.Hex._
import org.alephium.serde._

class HashSpec extends AlephiumSuite {

  def check[T <: HashOutput](provider: Hash[T], message: String, expected: Seq[Byte])(
      implicit serde: Serde[T]): Unit = {
    provider.getClass.getSimpleName should "hash correctly" in {
      val output = provider.hash(message)
      output.digest shouldBe expected
    }

    it should "serde correctly" in {
      val input  = provider.hash(message)
      val output = deserialize[T](serialize(input)).success.value
      output shouldBe input
    }
  }

  check(Sha256,
        "Hello World",
        hex"A591A6D40BF420404A011733CFB7B190D62C65BF0BCDA32B57B277D9AD9F146E")

  check(Keccak256,
        "Hello World",
        hex"592fa743889fc7f92ac2a37bb1f5ba1daf2a5c84741ca0e0061d243a2e6707ba")
}
