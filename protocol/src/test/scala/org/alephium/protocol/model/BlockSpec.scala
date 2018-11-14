package org.alephium.protocol.model

import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.serde._
import org.alephium.util.AlephiumSpec
import org.scalatest.TryValues._

class BlockSpec extends AlephiumSpec with ConsensusConfigFixture {

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
      block.hash is block.blockHeader.hash
    }
  }
}
