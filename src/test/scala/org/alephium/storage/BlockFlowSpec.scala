package org.alephium.storage

import org.alephium.AlephiumSpec
import org.alephium.constant.Network
import org.alephium.protocol.model.Block
import org.alephium.storage.BlockFlow.ChainIndex

import scala.annotation.tailrec

class BlockFlowSpec extends AlephiumSpec {
  behavior of "BlockFlow"

  it should "compute correct blockflow height" in {
    val blockFlow = BlockFlow()
    Network.blocksForFlow.flatten.foreach { block =>
      blockFlow.getBlockFlowHeight(block) is Network.chainNum
    }
  }

  it should "work for at least 2 user group when adding blocks sequentially" in {
    if (Network.groups >= 2) {
      val blockFlow = BlockFlow()

      val chainIndex1 = ChainIndex(0, 0)
      val block1      = mine(blockFlow, chainIndex1)
      blockFlow.addBlocks(Seq(block1))
      blockFlow.getBlockFlowHeight(block1) is (Network.chainNum + 1)

      val chainIndex2 = ChainIndex(1, 1)
      val block2      = mine(blockFlow, chainIndex2)
      blockFlow.addBlocks(Seq(block2))
      blockFlow.getBlockFlowHeight(block2) is (Network.chainNum + 2)

      val chainIndex3 = ChainIndex(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      blockFlow.addBlocks(Seq(block3))
      blockFlow.getBlockFlowHeight(block3) is (Network.chainNum + 3) // groups + 2 + groups + 1 + 2 * (groups - 2)

      val chainIndex4 = ChainIndex(0, 0)
      val block4      = mine(blockFlow, chainIndex4)
      blockFlow.addBlocks(Seq(block4))
      blockFlow.getBlockFlowHeight(block4) is (Network.chainNum + 4)
    }
  }

  it should "work for at least 2 user group when adding blocks in parallel" in {
    if (Network.groups >= 2) {
      val blockFlow = BlockFlow()

      val newBlocks1 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks1.foreach { block =>
        blockFlow.addBlocks(Seq(block))
        blockFlow.getBlockFlowHeight(block) is (Network.chainNum + 1)
      }

      val newBlocks2 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks2.foreach { block =>
        blockFlow.addBlocks(Seq(block))
        blockFlow.getBlockFlowHeight(block) is (Network.chainNum + 4)
      }

      val newBlocks3 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks3.foreach { block =>
        blockFlow.addBlocks(Seq(block))
        blockFlow.getBlockFlowHeight(block) is (Network.chainNum + 8)
      }
    }
  }

  it should "work for 2 user group when there is forks" in {
    if (Network.groups >= 2) {
      val blockFlow = BlockFlow()

      val chainIndex1 = ChainIndex(0, 0)
      val block11     = mine(blockFlow, chainIndex1)
      val block12     = mine(blockFlow, chainIndex1)
      blockFlow.add(block11)
      blockFlow.add(block12)
      blockFlow.getBlockFlowHeight(block11) is (Network.chainNum + 1)
      blockFlow.getBlockFlowHeight(block12) is (Network.chainNum + 1)

      val block13 = mine(blockFlow, chainIndex1)
      blockFlow.add(block13)
      blockFlow.getBlockFlowHeight(block13) is (Network.chainNum + 2)

      val chainIndex2 = ChainIndex(1, 1)
      val block21     = mine(blockFlow, chainIndex2)
      val block22     = mine(blockFlow, chainIndex2)
      blockFlow.add(block21)
      blockFlow.add(block22)
      blockFlow.getBlockFlowHeight(block21) is (Network.chainNum + 3)
      blockFlow.getBlockFlowHeight(block22) is (Network.chainNum + 3)

      val chainIndex3 = ChainIndex(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      blockFlow.addBlocks(Seq(block3))
      blockFlow.getBlockFlowHeight(block3) is (Network.chainNum + 4)
    }
  }

  def mine(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    val deps = blockFlow.getBestDeps(chainIndex)._1

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, Seq.empty, nonce)
      if (chainIndex.accept(block.hash)) block else iter(nonce + 1)
    }

    iter(0)
  }
}
