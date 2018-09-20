package org.alephium.storage

import org.alephium.AlephiumSpec
import org.alephium.protocol.model.{Block, ModelGen}

class ForksTreeSpec extends AlephiumSpec {
  behavior of "ForksTree"

  it should "work for a single chain" in {
    forAll(ModelGen.chainGen(5), minSuccessful(1), maxDiscardedFactor(1.0)) { blocks =>
      val forksTree = createForksTree(blocks.init)
      blocks.init.foreach(block => forksTree.contains(block) is true)
      forksTree.maxHeight is 3
      forksTree.maxWeight is 6
      forksTree.add(blocks.last, 8)
      forksTree.contains(blocks.last) is true
      forksTree.maxHeight is 4
      forksTree.maxWeight is 8
    }
  }

  it should "work for two chains with same root" in {
    forAll(ModelGen.chainGen(5), minSuccessful(1)) { blocks1 =>
      forAll(ModelGen.chainGen(1, blocks1.head), minSuccessful(1)) { blocks2 =>
        val forksTree = createForksTree(blocks1)
        blocks2.foreach(block => forksTree.add(block, 0))
        blocks1.foreach(block => forksTree.contains(block) is true)
        blocks2.foreach(block => forksTree.contains(block) is true)
        forksTree.maxHeight is 4
        forksTree.maxWeight is 8
        forksTree.getBlocks(blocks1.head.hash).size is 5
        forksTree.getBlocks(blocks2.head.hash).size is 0
        forksTree.getBlocks(blocks1.tail.head.hash).size is 3
      }
    }
  }

  def createForksTree(blocks: Seq[Block]): ForksTree = {
    assert(blocks.nonEmpty)
    val forksTree = ForksTree(blocks.head, 0, 0)
    blocks.zipWithIndex.tail foreach {
      case (block, index) =>
        forksTree.add(block, index * 2)
    }
    forksTree
  }
}
