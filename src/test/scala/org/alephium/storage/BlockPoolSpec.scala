package org.alephium.storage

import org.alephium.AlephiumSpec
import org.alephium.constant.Protocol
import org.alephium.protocol.model.{Block, ModelGen}

class BlockPoolSpec extends AlephiumSpec {
  val genesis: Block = Protocol.Genesis.block

  behavior of "BlockPool"

  it should "contains genesis block in the beginning" in {
    val pool  = BlockPool()
    val chain = pool.getBestChain
    chain.size shouldBe 1
    val block = chain.head
    block shouldBe genesis
  }

  it should "add a new block and increase the chain length" in {
    forAll(ModelGen.blockGenWith(Seq(genesis.hash))) { block =>
      val pool = BlockPool()
      pool.addBlock(block)
      pool.getBestHeader shouldBe block
      val chain = pool.getBestChain
      chain.size shouldBe 2
      chain.last shouldBe block
    }
  }

  it should "add a side block and keep the chain length" in {
    forAll(ModelGen.blockGen) { block =>
      val pool = BlockPool()
      pool.addBlock(block)
      val chain = pool.getBestChain
      chain.size shouldBe 1
    }
  }

  it should "add two sequential blocks and increase the chain length" in {
    forAll(ModelGen.blockGenWith(Seq(genesis.hash))) { block1 =>
      forAll(ModelGen.blockGenWith(Seq(block1.hash))) { block2 =>
        val pool = BlockPool()
        pool.addBlocks(Seq(block1, block2))
        pool.getBestHeader shouldBe block2
        val chain = pool.getBestChain
        chain.size shouldBe 3
        chain(1) shouldBe block1
        chain(2) shouldBe block2
      }
    }
  }
}
