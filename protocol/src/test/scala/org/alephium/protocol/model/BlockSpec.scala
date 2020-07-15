package org.alephium.protocol.model

import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.serde._
import org.alephium.util.AlephiumSpec

class BlockSpec extends AlephiumSpec with ConsensusConfigFixture with ModelGenerators {
  it should "serde" in {
    forAll(blockGen) { block =>
      val bytes  = serialize[Block](block)
      val output = deserialize[Block](bytes).toOption.get
      output is block
    }
  }

  it should "hash" in {
    forAll(blockGen) { block =>
      block.hash is block.header.hash
    }
  }
}
