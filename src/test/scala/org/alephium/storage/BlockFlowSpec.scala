package org.alephium.storage

import org.alephium.AlephiumSpec
import org.alephium.constant.Network
import org.alephium.protocol.model.Block
import org.alephium.storage.BlockFlow.ChainIndex

import scala.annotation.tailrec

class BlockFlowSpec extends AlephiumSpec {

  val blocks1 = Network.createBlockFlow(1)
  val blocks2 = Network.createBlockFlow(2)

  behavior of "BlockFlow"

  it should "compute correct blockflow height" in {
    val blockFlow1 = BlockFlow(1, blocks1)
    blocks1.flatten.foreach { block =>
      blockFlow1.getBlockFlowHeight(block) is 1
    }

    val blockFlow2 = BlockFlow(2, blocks2)
    blocks2.flatten.foreach { block =>
      blockFlow2.getBlockFlowHeight(block) is 1
    }
  }

  it should "work for 1 user group" in {
    val chainIndex = ChainIndex(0, 0)
    val blockFlow  = BlockFlow(1, blocks1)
    val deps1      = blockFlow.getBestDeps(chainIndex)

    blockFlow.getHeight is 1
    deps1.size is 1
    deps1.head is blocks1.head.head.hash

    val newBlock = mine(blockFlow, ChainIndex(0, 0))
    blockFlow.addBlocks(Seq(newBlock))
    val deps2 = blockFlow.getBestDeps(chainIndex)

    blockFlow.getHeight is 2
    deps2.size is 1
    deps2.head is newBlock.hash
  }

  it should "work for 2 user group when adding blocks sequentially" in {
    val blockFlow = BlockFlow(2, blocks2)

    val chainIndex1 = ChainIndex(0, 0)
    val block1      = mine(blockFlow, chainIndex1)
    blockFlow.addBlocks(Seq(block1))
    blockFlow.getBlockFlowHeight(block1) is 4

    val chainIndex2 = ChainIndex(1, 1)
    val block2      = mine(blockFlow, chainIndex2)
    blockFlow.addBlocks(Seq(block2))
    blockFlow.getBlockFlowHeight(block2) is 6

    val chainIndex3 = ChainIndex(0, 1)
    val block3      = mine(blockFlow, chainIndex3)
    blockFlow.addBlocks(Seq(block3))
    blockFlow.getBlockFlowHeight(block3) is 7
  }

  it should "work for 2 user group when adding blocks in parallel" in {
    val blockFlow = BlockFlow(2, blocks2)

    val newBlocks1 = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield mine(blockFlow, ChainIndex(i, j))
    newBlocks1.foreach { block =>
      blockFlow.getBlockFlowHeight(block) is 4
      blockFlow.addBlocks(Seq(block))
    }

    val newBlocks2 = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield mine(blockFlow, ChainIndex(i, j))
    newBlocks2.foreach { block =>
      blockFlow.getBlockFlowHeight(block) is 8
      blockFlow.addBlocks(Seq(block))
    }
  }

  def mine(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    val deps = blockFlow.getBestDeps(chainIndex)

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, Seq.empty, nonce)
      if (chainIndex.accept(block.miningHash)) block else iter(nonce + 1)
    }

    iter(0)
  }
}
