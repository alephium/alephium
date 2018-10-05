package org.alephium.protocol.model

import org.alephium.serde.deserialize
import org.alephium.util.AlephiumSpec
import org.scalatest.TryValues._

class SerdeSpec extends AlephiumSpec {

  "Block" should "be serde correctly" in {
    forAll(ModelGen.blockGen) { input =>
      val output = deserialize[Block](input.bytes).success.value
      output is input
    }
  }
}
