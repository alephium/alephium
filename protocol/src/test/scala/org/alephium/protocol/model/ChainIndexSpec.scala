package org.alephium.protocol.model

import org.alephium.util.AlephiumSpec

class ChainIndexSpec extends AlephiumSpec with NoIndexModelGenerators {
  it should "check when it's intra group index" in {
    val index0 = ChainIndex.unsafe(0, 0)
    val index1 = ChainIndex.unsafe(0, 1)
    val index2 = ChainIndex.unsafe(1, 0)
    val index3 = ChainIndex.unsafe(1, 1)
    index0.isIntraGroup is true
    index1.isIntraGroup is false
    index2.isIntraGroup is false
    index3.isIntraGroup is true
  }

  it should "compute the correct index" in {
    forAll(blockGen) { block =>
      val index = block.chainIndex

      val hash2Int = BigInt(1, block.hash.bytes.takeRight(2).toArray)
      val rawIndex = (hash2Int % groupConfig.chainNum).toInt
      index.from.value is rawIndex / groupConfig.groups
      index.to.value is rawIndex % groupConfig.groups
    }
  }

  it should "equalize same values" in {
    forAll(groupIndexGen, groupIndexGen) { (from, to) =>
      val index1 = ChainIndex(from, to)
      val index2 = ChainIndex(from, to)
      index1 is index2
    }

    val index1 = new ChainIndex(new GroupIndex(999999), new GroupIndex(999999))
    val index2 = new ChainIndex(new GroupIndex(999999), new GroupIndex(999999))
    index1 is index2
  }
}
