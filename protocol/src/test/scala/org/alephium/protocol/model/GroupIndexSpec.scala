package org.alephium.protocol.model

import org.alephium.util.AlephiumSpec

class GroupIndexSpec extends AlephiumSpec with NoIndexModelGenerators {
  it should "equalize same values" in {
    forAll(groupIndexGen) { n =>
      val groupIndex1 = GroupIndex.unsafe(n.value)(groupConfig)
      val groupIndex2 = GroupIndex.unsafe(n.value)(groupConfig)
      groupIndex1 is groupIndex2
    }

    val index1 = new GroupIndex(999999)
    val index2 = new GroupIndex(999999)
    index1 is index2
  }
}
