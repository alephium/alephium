package org.alephium.protocol.model

import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.util.AlephiumSpec

class GroupIndexSpec extends AlephiumSpec with ConsensusConfigFixture with ModelGenerators {
  behavior of "GroupIndex"

  it should "equalize same values" in {
    forAll(groupIndexGen) { n =>
      val groupIndex1 = GroupIndex.unsafe(n.value)(consensusConfig)
      val groupIndex2 = GroupIndex.unsafe(n.value)(consensusConfig)
      groupIndex1 is groupIndex2
    }

    val index1 = new GroupIndex(999999)
    val index2 = new GroupIndex(999999)
    index1 is index2
  }
}
