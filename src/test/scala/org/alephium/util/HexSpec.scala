package org.alephium.util

import org.alephium.AlephiumSuite
import org.alephium.util.Hex._

class HexSpec extends AlephiumSuite {
  "Hex" should "interpolate string correctly" in {
    val input = hex"666f6f626172"
    input.map(_.toChar).mkString("") shouldBe "foobar"
  }
}
