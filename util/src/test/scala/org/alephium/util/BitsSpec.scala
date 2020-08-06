package org.alephium.util

class BitsSpec extends AlephiumSpec {
  it should "convert for special cases" in {
    val zero = AVector.fill(8)(false)

    Bits.from(0) is zero
    Bits.from(-1) is AVector.fill(8)(true)
    0 until 8 foreach { k =>
      Bits.from((1 << k).toByte) is zero.replace(7 - k, true)
    }

    Bits.toInt(zero) is 0
    Bits.toInt(AVector.fill(10)(true)) is 1023
  }

  it should "convert general byte" in {
    forAll { byte: Byte =>
      Bits.toInt(Bits.from(byte)) is Bytes.toPosInt(byte)
    }
  }
}
