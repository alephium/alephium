package org.alephium.util

import java.nio.ByteBuffer

import scala.language.postfixOps

import akka.util.ByteString

class BytesSpec extends AlephiumSpec {
  it should "convert byte into positive int" in {
    forAll { input: Byte =>
      val output = Bytes.toPosInt(input)
      output >= 0 is true
      output.toByte is input
    }
  }

  it should "convert int to correct bytes" in {
    forAll { input: Int =>
      val output   = Bytes.from(input)
      val expected = ByteBuffer.allocate(4).putInt(input).array()
      output is ByteString.fromArrayUnsafe(expected)

      Bytes.toIntUnsafe(Bytes.from(input)) is input
    }
  }

  it should "convert long to correct bytes" in {
    forAll { input: Long =>
      val output   = Bytes.toBytes(input)
      val expected = ByteBuffer.allocate(8).putLong(input).array()
      output is ByteString.fromArrayUnsafe(expected)

      Bytes.toLongUnsafe(Bytes.toBytes(input)) is input
    }
  }

  it should "compute correct xor byte for int" in {
    forAll { input: Int =>
      val output   = Bytes.xorByte(input)
      val bytes    = Bytes.from(input)
      val expected = bytes.tail.fold(bytes.head)(_ ^ _ toByte)
      output is expected
    }
  }

  it should "compare byte string" in {
    import Bytes.byteStringOrdering
    Seq(ByteString.empty, ByteString(0)).max is ByteString(0)
    Seq(ByteString(0), ByteString(1)).max is ByteString(1)
    Seq(ByteString(0), ByteString(0, 1)).max is ByteString(0, 1)
    Seq(ByteString(0, 0), ByteString(0, 1)).max is ByteString(0, 1)
  }
}
