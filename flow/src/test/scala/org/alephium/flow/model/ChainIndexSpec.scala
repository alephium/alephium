package org.alephium.flow.model

import org.alephium.flow.constant.Network
import org.alephium.protocol.model.ModelGen
import org.alephium.util.AlephiumSpec

class ChainIndexSpec extends AlephiumSpec {

  behavior of "ChainIndex"

  it should "compute the correct index" in {
    forAll(ModelGen.blockGen, minSuccessful(1)) { block =>
      val hash       = block.hash
      val miningHash = block.miningHash
      val index      = ChainIndex.fromHash(hash)
      index.accept(hash) is true

      val hash2Int = BigInt(1, miningHash.bytes.takeRight(2).toArray)
      val rawIndex = (hash2Int % Network.chainNum).toInt
      index.from is rawIndex / Network.groups
      index.to is rawIndex % Network.groups
    }
  }
}
