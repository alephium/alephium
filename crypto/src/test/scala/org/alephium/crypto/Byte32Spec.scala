package org.alephium.crypto

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.serde._
import org.alephium.util.AlephiumSpec

class Byte32Spec extends AlephiumSpec {
  it should "build Byte32" in {
    def test(bs: ByteString, validity: Boolean): Assertion = {
      if (validity) {
        Byte32.unsafe(bs).bytes is bs
        Byte32.from(bs).get.bytes is bs
      } else {
        assertThrows[AssertionError](Byte32.unsafe(bs))
        Byte32.from(bs) is None
      }
    }

    test(ByteString.empty, false)
    test(ByteString.fromArray(Array.fill[Byte](31)(0)), false)
    test(ByteString.fromArray(Array.fill[Byte](32)(0)), true)
    test(ByteString.fromArray(Array.fill[Byte](33)(0)), false)
  }

  it should "serde Byte32" in {
    val b32 = Byte32.from(ByteString.fromArrayUnsafe(Array.fill[Byte](32)(1))).get
    deserialize[Byte32](serialize(b32)) isE b32
  }
}
