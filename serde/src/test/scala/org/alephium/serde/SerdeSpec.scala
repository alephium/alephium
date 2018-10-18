package org.alephium.serde

import akka.util.ByteString
import org.alephium.serde.Serde.{ByteSerde, IntSerde, LongSerde}
import org.alephium.util.{AVector, AlephiumSpec}
import org.scalacheck.Gen
import org.scalacheck.Arbitrary.arbByte
import org.scalatest.TryValues._

class SerdeSpec extends AlephiumSpec {

  implicit val bytesGen: Gen[AVector[Byte]] = Gen.listOf(arbByte.arbitrary).map(AVector.from)

  def checkException[T](serde: FixedSizeSerde[T]): Unit = {
    it should "throw correct exceptions when error occurs" in {
      forAll { inputs: Array[Byte] =>
        lazy val exception = serde.deserialize(ByteString(inputs)).failure.exception
        if (inputs.length < serde.serdeSize) {
          exception is a[NotEnoughBytesException]
        } else if (inputs.length > serde.serdeSize) {
          exception is a[WrongFormatException]
        }
      }
    }
  }

  "Serde for Byte" should "serialize Byte into 1 byte" in {
    Serde[Byte].asInstanceOf[FixedSizeSerde[Byte]].serdeSize is 1
  }

  it should "serde correctly" in {
    forAll { b: Byte =>
      val bb = deserialize[Byte](serialize(b))
      bb.success.value is b
    }

    forAll { b: Byte =>
      val input  = ByteString(b)
      val output = serialize(deserialize[Byte](input).success.value)
      output is input
    }
  }

  checkException(ByteSerde)

  "Serde for Int" should "serialize int into 4 bytes" in {
    Serde[Int].asInstanceOf[FixedSizeSerde[Int]].serdeSize is 4
  }

  it should "serde correctly" in {
    forAll { n: Int =>
      val nn = deserialize[Int](serialize(n))
      nn.success.value is n
    }

    forAll { (a: Byte, b: Byte, c: Byte, d: Byte) =>
      val input  = ByteString(a, b, c, d)
      val output = serialize(deserialize[Int](input).success.value)
      output is input
    }
  }

  checkException(IntSerde)

  "Serde for Long" should "serialize long into 8 bytes" in {
    Serde[Long].asInstanceOf[FixedSizeSerde[Long]].serdeSize is 8
  }

  it should "serde correctly" in {
    forAll { n: Long =>
      val nn = deserialize[Long](serialize(n))
      nn.success.value is n
    }
  }

  checkException(LongSerde)

  "Serde for BigInt" should "serde correctly" in {
    forAll { n: Long =>
      val bn  = BigInt(n)
      val bnn = deserialize[BigInt](serialize(bn)).success.value
      bnn is bn
    }
  }

  "Serde for fixed size sequence" should "serde correctly" in {
    forAll { input: AVector[Byte] =>
      {
        val serde  = Serde.fixedSizeBytesSerde(input.length, Serde[Byte])
        val output = serde.deserialize(serde.serialize(input)).success.value
        output is input
      }
      {
        val serde     = Serde.fixedSizeBytesSerde(input.length + 1, Serde[Byte])
        val exception = serde.deserialize(ByteString(input.toArray)).failure.exception
        exception is a[NotEnoughBytesException]
      }

      if (input.nonEmpty) {
        val serde     = Serde.fixedSizeBytesSerde(input.length - 1, Serde[Byte])
        val exception = serde.deserialize(ByteString(input.toArray)).failure.exception
        exception is a[WrongFormatException]
      }
    }
  }

  "Serde for sequence" should "serde correctly" in {
    forAll { input: AVector[Byte] =>
      val serde  = Serde.dynamicSizeBytesSerde(Serde[Byte])
      val output = serde.deserialize(serde.serialize(input)).success.value
      output is input
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
      output.success.value is input
    }
  }

  it should "serde two fields correctly" in {
    forAll { (x: Int, y: Int) =>
      val input  = Test2(x, y)
      val output = deserialize[Test2](serialize(input))
      output.success.value is input
    }
  }

  it should "serde three fields correctly" in {
    forAll { (x: Int, y: Int, z: Int) =>
      val input  = Test3(x, y, z)
      val output = deserialize[Test3](serialize(input))
      output.success.value is input
    }
  }
}
