package org.alephium.protocol.model

import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.util.AlephiumSpec

class ChainIndexSpec extends AlephiumSpec with ConsensusConfigFixture {

  behavior of "ChainIndex"

  it should "compute the correct index" in {
    forAll(ModelGen.blockGen) { block =>
      val index = block.chainIndex

      val hash2Int = BigInt(1, block.hash.bytes.takeRight(2).toArray)
      val rawIndex = (hash2Int % consensusConfig.chainNum).toInt
      index.from.value is rawIndex / consensusConfig.groups
      index.to.value is rawIndex % consensusConfig.groups
    }
  }

  it should "equalize same values" in {
    forAll(ModelGen.groupGen, ModelGen.groupGen) { (from, to) =>
      val index1 = ChainIndex(from, to)
      val index2 = ChainIndex(from, to)
      index1 is index2
    }

    val index1 = ChainIndex.unsafe(999999, 999999)
    val index2 = ChainIndex.unsafe(999999, 999999)
    index1 is index2
  }
}
