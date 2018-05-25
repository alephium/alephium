package org.alephium.storage

import org.alephium.AlephiumSpec
import org.alephium.flow.ChainSlice
import org.alephium.protocol.model.ModelGen

class ForksTreeSpec extends AlephiumSpec {
  behavior of "ForksTree"

  it should "work for a single chain" in {
    forAll(ModelGen.chainGen(5), minSuccessful(1), maxDiscardedFactor(1.0)) { blocks =>
      val chainSlice = ChainSlice(blocks.init)
      val forksTree  = new ForksTree(chainSlice)
      blocks.init.foreach(block => forksTree.contains(block) is true)
      forksTree.weight is 4
      forksTree.add(blocks.last)
      forksTree.contains(blocks.last) is true
      forksTree.weight is 5
    }
  }

  it should "work for two chains with same root" in {
    forAll(ModelGen.chainGen(5), minSuccessful(1)) { blocks1 =>
      val chainSlice1 = ChainSlice(blocks1)
      forAll(ModelGen.chainGen(2, Seq(blocks1.head)), minSuccessful(1)) { blocks2 =>
        val forksTree = new ForksTree(chainSlice1)
        blocks2.foreach(forksTree.add)
        blocks1.foreach(block => forksTree.contains(block) is true)
        blocks2.foreach(block => forksTree.contains(block) is true)
        forksTree.weight is 5
      }
    }
  }
}
