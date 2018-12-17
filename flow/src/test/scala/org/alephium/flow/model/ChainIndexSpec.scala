package org.alephium.flow.model

import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{ChainIndex, ModelGen}
import org.alephium.util.AlephiumSpec

class ChainIndexSpec extends AlephiumSpec with PlatformConfig.Default {

  behavior of "ChainIndex"

  it should "compute the correct index" in {
    forAll(ModelGen.blockGen, minSuccessful(1)) { block =>
      val hash  = block.hash
      val index = ChainIndex.fromHash(hash)
      index.accept(block) is true

      val hash2Int = BigInt(1, hash.bytes.takeRight(2).toArray)
      val rawIndex = (hash2Int % config.chainNum).toInt
      index.from is rawIndex / config.groups
      index.to is rawIndex % config.groups
    }
  }
}
