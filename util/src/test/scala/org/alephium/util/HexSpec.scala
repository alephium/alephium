package org.alephium.util

import org.alephium.AlephiumSpec
import org.alephium.util.Hex._

class HexSpec extends AlephiumSpec {
  "Hex" should "interpolate string correctly" in {
    val input = hex"666f6f626172"
    input.map(_.toChar).mkString("") is "foobar"
  }
}
