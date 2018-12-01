package org.alephium.serde

import akka.util.ByteString
import org.alephium.AlephiumSuite
import org.scalatest.TryValues._

class SerdeSpec extends AlephiumSuite {
  "Serde for Byte" should "serialize Byte into 1 byte" in {
    implicitly[Serde[Byte]].serdeSize shouldBe 1
  }

  it should "serde correctly" in {
    forAll { (b: Byte) =>
      val bb = deserialize[Byte](serialize(b))
      bb.success.value shouldBe b
    }

    forAll { (b: Byte) =>
      val input  = ByteString(b)
      val output = serialize(deserialize[Byte](input).success.value)
      output shouldBe input
    }
  }

  "Serde for Int" should "serialize integer into 4 bytes" in {
    implicitly[Serde[Int]].serdeSize shouldBe 4
  }

  it should "serde correctly" in {
    forAll { (n: Int) =>
      val nn = deserialize[Int](serialize(n))
      nn.success.value shouldBe n
    }

    forAll { (a: Byte, b: Byte, c: Byte, d: Byte) =>
      val input  = ByteString(a, b, c, d)
      val output = serialize(deserialize[Int](input).success.value)
      output shouldBe input
    }
  }

  "Serde for fixed size array" should "serde correctly" in {
    forAll { (input: Array[Byte]) =>
      val serde  = Serde.fixedSizeBytesSerde(input.length, implicitly[Serde[Byte]])
      val output = serde.deserialize(serde.serialize(input)).success.value
      output shouldBe input
    }
  }

  case class Test(x: Int, y: Int, z: Int)

  "Serde for case class" should "serialize into correct number of bytes" in {
    implicitly[Serde[Test]].serdeSize shouldBe 4 * 3
  }

  it should "serde correctly" in {
    forAll { (x: Int, y: Int, z: Int) =>
      val input  = Test(x, y, z)
      val output = deserialize[Test](serialize(input))
      output.success.value shouldBe input
    }
  }
}
