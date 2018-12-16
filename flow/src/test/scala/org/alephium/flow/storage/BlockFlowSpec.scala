package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.constant.Consensus
import org.alephium.flow.model.ChainIndex
import org.alephium.protocol.model.Block
import org.alephium.util.{AlephiumSpec, Hex}

import scala.annotation.tailrec

class BlockFlowSpec extends AlephiumSpec with PlatformConfig.Default {
  behavior of "BlockFlow"

  it should "compute correct blockflow height" in {
    val blockFlow = BlockFlow()
    config.blocksForFlow.flatten.foreach { block =>
      blockFlow.getWeight(block) is 0
    }
  }

  it should "work for at least 2 user group when adding blocks sequentially" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow()

      val chainIndex1 = ChainIndex(0, 0)
      val block1      = mine(blockFlow, chainIndex1)
      blockFlow.add(block1)
      blockFlow.getWeight(block1) is 1

      val chainIndex2 = ChainIndex(1, 1)
      val block2      = mine(blockFlow, chainIndex2)
      blockFlow.add(block2)
      blockFlow.getWeight(block2) is 2

      val chainIndex3 = ChainIndex(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      blockFlow.add(block3)
      blockFlow.getWeight(block3) is 3

      val chainIndex4 = ChainIndex(0, 0)
      val block4      = mine(blockFlow, chainIndex4)
      blockFlow.add(block4)
      blockFlow.getWeight(block4) is 4
    }
  }

  it should "work for at least 2 user group when adding blocks in parallel" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow()

      val newBlocks1 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks1.foreach { block =>
        blockFlow.add(block)
        blockFlow.getWeight(block) is 1
      }

      val newBlocks2 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks2.foreach { block =>
        blockFlow.add(block)
        blockFlow.getWeight(block) is 4
      }

      val newBlocks3 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks3.foreach { block =>
        blockFlow.add(block)
        blockFlow.getWeight(block) is 8
      }
    }
  }

  it should "work for 2 user group when there is forks" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow()

      val chainIndex1 = ChainIndex(0, 0)
      val block11     = mine(blockFlow, chainIndex1)
      val block12     = mine(blockFlow, chainIndex1)
      blockFlow.add(block11)
      blockFlow.add(block12)
      blockFlow.getWeight(block11) is 1
      blockFlow.getWeight(block12) is 1

      val block13 = mine(blockFlow, chainIndex1)
      blockFlow.add(block13)
      blockFlow.getWeight(block13) is 2

      val chainIndex2 = ChainIndex(1, 1)
      val block21     = mine(blockFlow, chainIndex2)
      val block22     = mine(blockFlow, chainIndex2)
      blockFlow.add(block21)
      blockFlow.add(block22)
      blockFlow.getWeight(block21) is 3
      blockFlow.getWeight(block22) is 3

      val chainIndex3 = ChainIndex(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      blockFlow.add(block3)
      blockFlow.getWeight(block3) is 4
    }
  }

  def mine(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    val deps = blockFlow.getBestDeps(chainIndex).deps

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, Seq.empty, Consensus.maxMiningTarget, nonce)
      if (chainIndex.accept(block.hash)) block else iter(nonce + 1)
    }

    iter(0)
  }

  def show(blockFlow: BlockFlow): String = {
    blockFlow.getAllTips
      .map { tip =>
        val weight = blockFlow.getWeight(tip)
        val block  = blockFlow.getBlock(tip)
        val index  = blockFlow.getIndex(block)
        val hash   = showHash(tip)
        val deps   = block.blockHeader.blockDeps.map(showHash).mkString("-")
        s"weight: $weight, from: ${index.from}, to: ${index.to} hash: $hash, deps: $deps"
      }
      .mkString("\n")
  }

  def showHash(hash: Keccak256): String = {
    Hex.toHexString(hash.bytes).take(8)
  }
}
