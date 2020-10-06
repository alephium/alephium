package org.alephium.protocol.model

import org.scalatest.Assertion

import org.alephium.util.{AlephiumSpec, Hex}

class TargetSpec extends AlephiumSpec {
  it should "convert between big integer and compact bits" in {
    def check(bits: String): Assertion = {
      checkGeneric(bits, bits)
    }

    def checkGeneric(bits: String, expected: String): Assertion = {
      val bytes = Hex.from(bits).get
      Target.unsafe(Target.unsafe(bytes).value).bits is Hex.from(expected).get
    }

    check("00000000")
    check("03123456")
    check("04123456")
    check("12345667")
    check("20FFFFFF")

    checkGeneric("00123456", "00000000")
    checkGeneric("01123456", "01120000")
    checkGeneric("02123456", "02123400")
  }
}
