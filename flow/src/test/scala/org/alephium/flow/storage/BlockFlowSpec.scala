package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.{AlephiumFlowSpec, PlatformConfig}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Hex}
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import scala.annotation.tailrec

// TODO: test for more groups
class BlockFlowSpec extends AlephiumFlowSpec {
  behavior of "BlockFlow"

  it should "compute correct blockflow height" in {
    val blockFlow = BlockFlow.createUnsafe()
    config.genesisBlocks.flatMap(identity).foreach { block =>
      blockFlow.getWeight(block.hash) is 0
    }
  }

  it should "work for at least 2 user group when adding blocks sequentially" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow.createUnsafe()

      val chainIndex1 = ChainIndex(0, 0)
      val block1      = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block1)
      blockFlow.getWeight(block1) is 1
      checkInBestDeps(GroupIndex(0), blockFlow, block1)

      val chainIndex2 = ChainIndex(1, 1)
      val block2      = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block2.header)
      blockFlow.getWeight(block2.header) is 2
      checkInBestDeps(GroupIndex(0), blockFlow, block2)

      val chainIndex3 = ChainIndex(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3)
      blockFlow.getWeight(block3) is 3
      checkInBestDeps(GroupIndex(0), blockFlow, block3)

      val chainIndex4 = ChainIndex(0, 0)
      val block4      = mine(blockFlow, chainIndex4)
      addAndCheck(blockFlow, block4)
      blockFlow.getWeight(block4) is 4
      checkInBestDeps(GroupIndex(0), blockFlow, block4)
    }
  }

  it should "work for at least 2 user group when adding blocks in parallel" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow.createUnsafe()

      val newBlocks1 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks1.foreach { block =>
        val index = block.chainIndex
        if (index.from == GroupIndex(0) || index.to == GroupIndex(0)) {
          addAndCheck(blockFlow, block)
          blockFlow.getWeight(block) is 1
        } else {
          addAndCheck(blockFlow, block.header)
          blockFlow.getWeight(block.header) is 1
        }
      }
      checkInBestDeps(GroupIndex(0), blockFlow, newBlocks1)

      val newBlocks2 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks2.foreach { block =>
        val index = block.chainIndex
        if (index.from == GroupIndex(0) || index.to == GroupIndex(0)) {
          addAndCheck(blockFlow, block)
          blockFlow.getWeight(block) is 4
        } else {
          addAndCheck(blockFlow, block.header)
          blockFlow.getWeight(block.header) is 4
        }
      }
      checkInBestDeps(GroupIndex(0), blockFlow, newBlocks2)

      val newBlocks3 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks3.foreach { block =>
        val index = block.chainIndex
        if (index.from == GroupIndex(0) || index.to == GroupIndex(0)) {
          addAndCheck(blockFlow, block)
          blockFlow.getWeight(block) is 8
        } else {
          addAndCheck(blockFlow, block.header)
          blockFlow.getWeight(block.header) is 8
        }
      }
      checkInBestDeps(GroupIndex(0), blockFlow, newBlocks3)
    }
  }

  it should "work for 2 user group when there is forks" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow.createUnsafe()

      val chainIndex1 = ChainIndex(0, 0)
      val block11     = mine(blockFlow, chainIndex1)
      val block12     = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block11)
      addAndCheck(blockFlow, block12)
      blockFlow.getWeight(block11) is 1
      blockFlow.getWeight(block12) is 1
      checkInBestDeps(GroupIndex(0), blockFlow, IndexedSeq(block11, block12))

      val block13 = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block13)
      blockFlow.getWeight(block13) is 2
      checkInBestDeps(GroupIndex(0), blockFlow, block13)

      val chainIndex2 = ChainIndex(1, 1)
      val block21     = mine(blockFlow, chainIndex2)
      val block22     = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block21.header)
      addAndCheck(blockFlow, block22.header)
      blockFlow.getWeight(block21) is 3
      blockFlow.getWeight(block22) is 3
      checkInBestDeps(GroupIndex(0), blockFlow, IndexedSeq(block21, block22))

      val chainIndex3 = ChainIndex(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3)
      blockFlow.getWeight(block3) is 4
      checkInBestDeps(GroupIndex(0), blockFlow, block3)
    }
  }

  def mine(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    val deps = blockFlow.calBestDepsUnsafe(chainIndex.from).deps

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, AVector.empty[Transaction], config.maxMiningTarget, nonce)
      if (block.preValidate(chainIndex)) block else iter(nonce + 1)
    }

    iter(0)
  }

  def addAndCheck(blockFlow: BlockFlow, block: Block): Assertion = {
    blockFlow.add(block).isRight is true
  }

  def checkInBestDeps(groupIndex: GroupIndex, blockFlow: BlockFlow, block: Block): Assertion = {
    blockFlow.getBestDeps(groupIndex).deps.contains(block.hash) is true
  }

  def checkInBestDeps(groupIndex: GroupIndex,
                      blockFlow: BlockFlow,
                      blocks: IndexedSeq[Block]): Assertion = {
    val bestDeps = blockFlow.getBestDeps(groupIndex).deps
    blocks.exists { block =>
      bestDeps.contains(block.hash)
    } is true
  }

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader): Assertion = {
    blockFlow.add(header).isRight is true
  }

  def show(blockFlow: BlockFlow): String = {
    blockFlow.getAllTips
      .map { tip =>
        val weight = blockFlow.getWeight(tip)
        val header = blockFlow.getBlockHeaderUnsafe(tip)
        val index  = header.chainIndex
        val hash   = showHash(tip)
        val deps   = header.blockDeps.map(showHash).mkString("-")
        s"weight: $weight, from: ${index.from}, to: ${index.to} hash: $hash, deps: $deps"
      }
      .mkString("", "\n", "\n")
  }

  def showHash(hash: Keccak256): String = {
    Hex.toHexString(hash.bytes).take(8)
  }

  def getBalances(blockFlow: BlockFlow, groupIndex: GroupIndex): AVector[TxOutput] = {
    blockFlow.getBestTrie(groupIndex).getAll[TxOutput].right.value
  }

  def showBalances(blockFlow: BlockFlow)(implicit config: PlatformConfig): String = {
    def show(txOutput: TxOutput): String = {
      txOutput.mainKey.shortHex + ":" + txOutput.value
    }

    val txOutputs = config.brokerInfo.groupFrom until config.brokerInfo.groupUntil map { group =>
      val groupIndex = GroupIndex(group)
      getBalances(blockFlow, groupIndex)
    }
    txOutputs.map(_.map(show).mkString(";")) mkString ("", "\n", "\n")
  }
}
