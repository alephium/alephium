package org.alephium.serde

import akka.util.ByteString
import org.alephium.serde.Serde.{ByteSerde, IntSerde, LongSerde}
import org.scalatest.TryValues._
import org.scalatest.{FlatSpecLike, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class SerdeSpec extends FlatSpecLike with GeneratorDrivenPropertyChecks with Matchers {
  def checkException[T](serde: FixedSizeSerde[T]): Unit = {
    it should "throw correct exceptions when error occurs" in {
      forAll { inputs: Array[Byte] =>
        lazy val exception = serde.deserialize(ByteString(inputs)).failure.exception
        if (inputs.length < serde.serdeSize) {
          exception shouldBe a[NotEnoughBytesException]
        } else if (inputs.length > serde.serdeSize) {
          exception shouldBe a[WrongFormatException]
        }
      }
    }
  }

  "Serde for Byte" should "serialize Byte into 1 byte" in {
    implicitly[Serde[Byte]].asInstanceOf[FixedSizeSerde[Byte]].serdeSize shouldBe 1
  }

  it should "serde correctly" in {
    forAll { b: Byte =>
      val bb = deserialize[Byte](serialize(b))
      bb.success.value shouldBe b
    }

    forAll { b: Byte =>
      val input  = ByteString(b)
      val output = serialize(deserialize[Byte](input).success.value)
      output shouldBe input
    }
  }

  checkException(ByteSerde)

  "Serde for Int" should "serialize int into 4 bytes" in {
    implicitly[Serde[Int]].asInstanceOf[FixedSizeSerde[Int]].serdeSize shouldBe 4
  }

  it should "serde correctly" in {
    forAll { n: Int =>
      val nn = deserialize[Int](serialize(n))
      nn.success.value shouldBe n
    }

    forAll { (a: Byte, b: Byte, c: Byte, d: Byte) =>
      val input  = ByteString(a, b, c, d)
      val output = serialize(deserialize[Int](input).success.value)
      output shouldBe input
    }
  }

  checkException(IntSerde)

  "Serde for Long" should "serialize long into 8 bytes" in {
    implicitly[Serde[Long]].asInstanceOf[FixedSizeSerde[Long]].serdeSize shouldBe 8
  }

  it should "serde correctly" in {
    forAll { n: Long =>
      val nn = deserialize[Long](serialize(n))
      nn.success.value shouldBe n
    }
  }

  checkException(LongSerde)

  "Serde for BigInt" should "serde correctly" in {
    forAll { n: Long =>
      val bn  = BigInt(n)
      val bnn = deserialize[BigInt](serialize(bn)).success.value
      bnn shouldBe bn
    }
  }

  "Serde for fixed size sequence" should "serde correctly" in {
    forAll { input: Seq[Byte] =>
      {
        val serde  = Serde.fixedSizeBytesSerde(input.length, implicitly[Serde[Byte]])
        val output = serde.deserialize(serde.serialize(input)).success.value
        output shouldBe input
      }
      {
        val serde     = Serde.fixedSizeBytesSerde(input.length + 1, implicitly[Serde[Byte]])
        val exception = serde.deserialize(ByteString(input.toArray)).failure.exception
        exception shouldBe a[NotEnoughBytesException]
      }
      if (input.nonEmpty) {
        val serde     = Serde.fixedSizeBytesSerde(input.length - 1, implicitly[Serde[Byte]])
        val exception = serde.deserialize(ByteString(input.toArray)).failure.exception
        exception shouldBe a[WrongFormatException]
      }
    }
  }

  "Serde for sequence" should "serde correctly" in {
    forAll { input: Seq[Byte] =>
      val serde  = Serde.dynamicSizeBytesSerde(implicitly[Serde[Byte]])
      val output = serde.deserialize(serde.serialize(input)).success.value
      output shouldBe input
    }
  }

  case class Test1(x: Int)
  object Test1 {
    implicit val serde: Serde[Test1] = Serde.forProduct1(apply, t => t.x)
  }

  case class Test2(x: Int, y: Int)
  object Test2 {
    implicit val serde: Serde[Test2] = Serde.forProduct2(apply, t => (t.x, t.y))
  }

  case class Test3(x: Int, y: Int, z: Int)
  object Test3 {
    implicit val serde: Serde[Test3] = Serde.forProduct3(apply, t => (t.x, t.y, t.z))
  }

  "Serde for case class" should "serde one field correctly" in {
    forAll { x: Int =>
      val input  = Test1(x)
      val output = deserialize[Test1](serialize(input))
      output.success.value shouldBe input
    }
  }

  it should "serde two fields correctly" in {
    forAll { (x: Int, y: Int) =>
      val input  = Test2(x, y)
      val output = deserialize[Test2](serialize(input))
      output.success.value shouldBe input
    }
  }

  it should "serde three fields correctly" in {
    forAll { (x: Int, y: Int, z: Int) =>
      val input  = Test3(x, y, z)
      val output = deserialize[Test3](serialize(input))
      output.success.value shouldBe input
    }
  }
}
