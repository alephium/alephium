package org.alephium.util

import akka.util.ByteString

import org.alephium.util.Hex._

class HexSpec extends AlephiumSpec {
  it should "interpolate string correctly" in {
    val input = hex"666f6f626172"
    input.map(_.toChar).mkString("") is "foobar"
  }

  it should "decode correctly" in {
    val input    = "666f6f626172"
    val expected = ByteString.fromString("foobar")
    unsafe(input) is expected
    from(input).get is expected
  }

  it should "throw an error while decoding invalid input" in {
    val input = "666f6f62617"
    from(input).isEmpty is true
  }
}
