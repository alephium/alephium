package org.alephium.crypto

import akka.util.ByteString

import org.alephium.serde._
import org.alephium.util.AlephiumSpec
import org.alephium.util.Hex._

class HashSpec extends AlephiumSpec {
  def check[T <: RandomBytes](provider: HashSchema[T], message: String, expected: ByteString)(
      implicit serde: Serde[T]): Unit = {
    provider.getClass.getSimpleName should "hash correctly" in {
      val output = provider.hash(message)
      expected is output.bytes
    }

    it should "serde correctly" in {
      val input  = provider.hash(message)
      val output = deserialize[T](serialize(input)).toOption.get
      output is input
    }
  }

  def check[T <: RandomBytes](provider: HashSchema[T], tests: Map[String, ByteString])(
      implicit serde: Serde[T]): Unit = {
    provider.getClass.getSimpleName should "hash correctly" in {
      for ((message, expected) <- tests) {
        val output = provider.hash(message)
        output.bytes is expected
      }
    }

    it should "serde correctly" in {
      for ((message, _) <- tests) {
        val input  = provider.hash(message)
        val output = deserialize[T](serialize(input)).toOption.get
        output is input
      }
    }

    it should "compute hashCode correctly" in {
      for ((message, inHex) <- tests) {
        val output  = BigInt(provider.hash(message).hashCode())
        val expcted = BigInt(inHex.takeRight(4).toArray)
        output is expcted
      }
    }
  }

  check(
    Sha256,
    Map(
      "Hello World1" -> hex"6D1103674F29502C873DE14E48E9E432EC6CF6DB76272C7B0DAD186BB92C9A9A",
      "Hello World2" -> hex"0DE69F56365C10550D05E65AE8229DD0686F7894A807830DAEC8CAA879731F4D",
      "Hello World3" -> hex"DCE9D9544A7261B593A31C0C780BC2A4320D9E8D1CB98F02BAB71B722D741A99",
      "Hello World4" -> hex"8EC7C601D35E6059762698EE611D91AD150862DA29A3BA30365FC97AF2872E8E",
      "Hello World5" -> hex"5ADBF693486E51AA4C5BDBA658CD1E4040248063BDB3BE37FADE4DBDD398A5E0"
    )
  )

  check(
    Keccak256,
    Map(
      "Hello World1" -> hex"2744686CE50A2A5AE2A94D18A3A51149E2F21F7EEB4178DE954A2DFCADC21E3C",
      "Hello World2" -> hex"8143E0ED4DC593777C3E496013C8BE2D95875E9FCA46E81FC218CBA480E9A25F",
      "Hello World3" -> hex"1F92B7907A9564266AB8D351117090B96BE9D7B3C22172DA122106E253F61810",
      "Hello World4" -> hex"BA647048F3B513C233E3EBB60EC3B0122B49CE4D0A433254E03D54C1EE617D90",
      "Hello World5" -> hex"6CC1E7EC6FE939BB0EF7A10FE3D03DF052387B3CAF1416FD1CBAE3EF5C45657A"
    )
  )

  check(
    Blake2b,
    Map(
      "Hello World1" -> hex"8947bee8a082f643a8ceab187d866e8ec0be8c2d7d84ffa8922a6db77644b37a",
      "Hello World2" -> hex"4712c917c8ab6451d2c0adcc6af62926fec2d7ef1ee6a75282fca261b9a7b6a6",
      "Hello World3" -> hex"8f4c9c1048dc913ce1d3b031a7c52f108023385f2c240c390000b06c911064b6",
      "Hello World4" -> hex"b60cf83ffeb99da4b250ec680f7402bba6a6c75f3bdae16ef7ef0b4b762a9f4b",
      "Hello World5" -> hex"06bacc3a528511c9066a576813e80698e041f513ac478d31dd79b39e378f0dfe"
    )
  )
}
