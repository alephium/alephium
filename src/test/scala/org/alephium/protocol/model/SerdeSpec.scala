package org.alephium.protocol.model

import org.alephium.AlephiumSpec
import org.alephium.serde.deserialize
import org.scalatest.TryValues._

class SerdeSpec extends AlephiumSpec {

  "Block" should "be serde correctly" in {
    forAll(ModelGen.blockGen) { input =>
      val output = deserialize[Block](input.bytes).success.value
      output shouldBe input
    }
  }
}
