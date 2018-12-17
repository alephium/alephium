package org.alephium.protocol.model

import org.alephium.crypto.Keccak256
import org.alephium.serde._
import org.alephium.util.AlephiumSpec
import org.scalatest.TryValues._

class SerdeSpec extends AlephiumSpec {

  behavior of "Block"

  it should "serde" in {
    forAll(ModelGen.blockGen) { block =>
      val bytes  = serialize[Block](block)
      val output = deserialize[Block](bytes).success.value
      output is block
    }
  }

  it should "hash" in {
    forAll(ModelGen.blockGen) { block =>
      val hash = Keccak256.hash(serialize[Block](block))
      hash is block.hash
    }
  }
}
