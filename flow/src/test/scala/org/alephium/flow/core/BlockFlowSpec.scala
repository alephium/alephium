package org.alephium.flow.core

import scala.annotation.tailrec

import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.core.validation.Validation
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Hex}

// TODO: test for more groups
class BlockFlowSpec extends AlephiumFlowSpec {
  it should "compute correct blockflow height" in {
    val blockFlow = BlockFlow.createUnsafe()

    config.genesisBlocks.flatMap(identity).foreach { block =>
      blockFlow.getWeight(block.hash) is 0
    }

    checkBalance(blockFlow, config.brokerInfo.groupFrom, genesisBalance)
  }

  it should "work for at least 2 user group when adding blocks sequentially" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow.createUnsafe()

      val chainIndex1 = ChainIndex(0, 0)
      val block1      = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block1)
      blockFlow.getWeight(block1) is 1
      checkInBestDeps(GroupIndex(0), blockFlow, block1)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val chainIndex2 = ChainIndex(1, 1)
      val block2      = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block2.header)
      blockFlow.getWeight(block2.header) is 2
      checkInBestDeps(GroupIndex(0), blockFlow, block2)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val chainIndex3 = ChainIndex(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3)
      blockFlow.getWeight(block3) is 3
      checkInBestDeps(GroupIndex(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val chainIndex4 = ChainIndex(0, 0)
      val block4      = mine(blockFlow, chainIndex4)
      addAndCheck(blockFlow, block4)
      blockFlow.getWeight(block4) is 4
      checkInBestDeps(GroupIndex(0), blockFlow, block4)
      checkBalance(blockFlow, 0, genesisBalance - 2)
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
        if (index.relateTo(GroupIndex(0))) {
          addAndCheck(blockFlow, block)
          blockFlow.getWeight(block) is 1
        } else {
          addAndCheck(blockFlow, block.header)
          blockFlow.getWeight(block.header) is 1
        }
      }
      checkInBestDeps(GroupIndex(0), blockFlow, newBlocks1)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val newBlocks2 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks2.foreach { block =>
        val index = block.chainIndex
        if (index.relateTo(GroupIndex(0))) {
          addAndCheck(blockFlow, block)
          blockFlow.getWeight(block) is 4
        } else {
          addAndCheck(blockFlow, block.header)
          blockFlow.getWeight(block.header) is 4
        }
      }
      checkInBestDeps(GroupIndex(0), blockFlow, newBlocks2)
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val newBlocks3 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks3.foreach { block =>
        val index = block.chainIndex
        if (index.relateTo(GroupIndex(0))) {
          addAndCheck(blockFlow, block)
          blockFlow.getWeight(block) is 8
        } else {
          addAndCheck(blockFlow, block.header)
          blockFlow.getWeight(block.header) is 8
        }
      }
      checkInBestDeps(GroupIndex(0), blockFlow, newBlocks3)
      checkBalance(blockFlow, 0, genesisBalance - 3)
    }
  }

  it should "work for 2 user group when there is a fork" in {
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
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val block13 = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block13)
      blockFlow.getWeight(block13) is 2
      checkInBestDeps(GroupIndex(0), blockFlow, block13)
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex2 = ChainIndex(1, 1)
      val block21     = mine(blockFlow, chainIndex2)
      val block22     = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block21.header)
      addAndCheck(blockFlow, block22.header)
      blockFlow.getWeight(block21) is 3
      blockFlow.getWeight(block22) is 3
      checkInBestDeps(GroupIndex(0), blockFlow, IndexedSeq(block21, block22))
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex3 = ChainIndex(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3)
      blockFlow.getWeight(block3) is 4
      checkInBestDeps(GroupIndex(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - 2)
    }
  }

  it should "update mempool correctly" in {
    if (config.groups >= 2) {
      val broker = config.brokerInfo
      forAll(Gen.choose(broker.groupFrom, broker.groupUntil - 1)) { mainGroup =>
        val blockFlow = BlockFlow.createUnsafe()

        val chainIndex = ChainIndex.unsafe(mainGroup, 0)
        val block11    = mine(blockFlow, chainIndex)
        val block12    = mine(blockFlow, chainIndex)
        blockFlow.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block11)
        blockFlow.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block12)

        val blockAdded = blockFlow.getBestDeps(chainIndex.from).getChainHash(chainIndex.to)
        if (blockAdded equals block12.hash) {
          blockAdded is block12.hash
          blockFlow.getPool(chainIndex).size is block11.transactions.length
          val template = blockFlow.prepareBlockFlow(chainIndex).right.value
          template.transactions.length is block11.transactions.length
        }
      }
    }
  }

  def mine(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    val deps = blockFlow.calBestDepsUnsafe(chainIndex.from).deps
    val transactions = {
      if (chainIndex.isIntraGroup && chainIndex.relateTo(config.brokerInfo)) {
        val mainGroup                  = chainIndex.from
        val (privateKey, publicKey, _) = genesisBalances(mainGroup.value)
        val balances                   = blockFlow.getP2pkhUtxos(publicKey).right.value
        val total                      = balances.sumBy(_._2.value)
        val (_, toPublicKey)           = mainGroup.generateP2pkhKey
        val inputs                     = balances.map(_._1)
        val outputs                    = AVector(TxOutput.p2pkh(1, toPublicKey), TxOutput.p2pkh(total - 1, publicKey))
        AVector(Transaction.from(inputs, outputs, publicKey, privateKey))
      } else AVector.empty[Transaction]
    }

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, transactions, config.maxMiningTarget, nonce)
      if (Validation.validateMined(block, chainIndex)) block else iter(nonce + 1)
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

  def checkBalance(blockFlow: BlockFlow, groupIndex: Int, expected: BigInt): Assertion = {
    val address = genesisBalances(groupIndex)._2
    blockFlow.getP2pkhUtxos(address).right.value.sumBy(_._2.value) is expected
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

  def getBalance(blockFlow: BlockFlow, address: ED25519PublicKey): BigInt = {
    val groupIndex = GroupIndex.fromP2PKH(address)
    config.brokerInfo.contains(groupIndex) is true
    val query = blockFlow.getP2pkhUtxos(address)
    query.right.value.sumBy(_._2.value)
  }

  def showBalances(blockFlow: BlockFlow): Unit = {
    def show(txOutput: TxOutput): String = {
      txOutput.shortKey + ":" + txOutput.value
    }

    val address   = genesisBalances(config.brokerInfo.id)._2
    val txOutputs = blockFlow.getP2pkhUtxos(address).right.value.map(_._2)
    print(txOutputs.map(show).mkString("", ";", "\n"))
  }
}
