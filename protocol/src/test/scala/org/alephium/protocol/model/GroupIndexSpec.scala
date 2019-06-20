package org.alephium.protocol.model

import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.util.AlephiumSpec

class GroupIndexSpec extends AlephiumSpec with ConsensusConfigFixture {
  behavior of "GroupIndex"

  it should "equalize same values" in {
    forAll(ModelGen.groupIndexGen) { n =>
      val groupIndex1 = GroupIndex(n.value)(consensusConfig)
      val groupIndex2 = GroupIndex(n.value)(consensusConfig)
      groupIndex1 is groupIndex2
    }

    val index1 = GroupIndex.unsafe(999999)
    val index2 = GroupIndex.unsafe(999999)
    index1 is index2
  }
}
