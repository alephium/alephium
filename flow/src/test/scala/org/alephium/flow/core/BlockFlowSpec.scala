package org.alephium.flow.core

import scala.annotation.tailrec

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.core.validation.Validation
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model._
import org.alephium.protocol.script.PayTo
import org.alephium.util.{AVector, Hex}

class BlockFlowSpec extends AlephiumFlowSpec {
  it should "compute correct blockflow height" in {
    val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

    config.genesisBlocks.flatMap(identity).foreach { block =>
      blockFlow.getWeight(block.hash) isE 0
    }

    checkBalance(blockFlow, config.brokerInfo.groupFrom, genesisBalance)
  }

  it should "work for at least 2 user group when adding blocks sequentially" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

      val chainIndex1 = ChainIndex.unsafe(0, 0)
      val block1      = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block1, 1)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block1)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val chainIndex2 = ChainIndex.unsafe(1, 1)
      val block2      = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block2.header, 2)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block2)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val chainIndex3 = ChainIndex.unsafe(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3, 3)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex4 = ChainIndex.unsafe(0, 0)
      val block4      = mine(blockFlow, chainIndex4)
      addAndCheck(blockFlow, block4, 4)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block4)
      checkBalance(blockFlow, 0, genesisBalance - 3)
    }
  }

  it should "work for at least 2 user group when adding blocks in parallel" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

      val newBlocks1 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
      newBlocks1.foreach { block =>
        val index = block.chainIndex
        if (index.relateTo(GroupIndex.unsafe(0))) {
          addAndCheck(blockFlow, block, 1)
          blockFlow.getWeight(block) isE config.maxMiningTarget * 1
        } else {
          addAndCheck(blockFlow, block.header, 1)
        }
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks1)
      checkBalance(blockFlow, 0, genesisBalance - 1)
      newBlocks1.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true

      val newBlocks2 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
      newBlocks2.foreach { block =>
        val index = block.chainIndex
        if (index.relateTo(GroupIndex.unsafe(0))) {
          addAndCheck(blockFlow, block, 4)
          blockFlow.getChainWeight(block.hash) isE config.maxMiningTarget * 2
        } else {
          addAndCheck(blockFlow, block.header, 4)
        }
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks2)
      checkBalance(blockFlow, 0, genesisBalance - 2)
      newBlocks2.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true

      val newBlocks3 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
      newBlocks3.foreach { block =>
        val index = block.chainIndex
        if (index.relateTo(GroupIndex.unsafe(0))) {
          addAndCheck(blockFlow, block, 8)
          blockFlow.getChainWeight(block.hash) isE config.maxMiningTarget * 3
        } else {
          addAndCheck(blockFlow, block.header, 8)
        }
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks3)
      checkBalance(blockFlow, 0, genesisBalance - 3)
      newBlocks3.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true
    }
  }

  it should "work for 2 user group when there is a fork" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

      val chainIndex1 = ChainIndex.unsafe(0, 0)
      val block11     = mine(blockFlow, chainIndex1)
      val block12     = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block11, 1)
      addAndCheck(blockFlow, block12, 1)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, IndexedSeq(block11, block12))
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val block13 = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block13, 2)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block13)
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex2 = ChainIndex.unsafe(1, 1)
      val block21     = mine(blockFlow, chainIndex2)
      val block22     = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block21.header, 3)
      addAndCheck(blockFlow, block22.header, 3)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, IndexedSeq(block21, block22))
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex3 = ChainIndex.unsafe(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3, 4)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - 3)
    }
  }

  it should "update mempool correctly" in {
    if (config.groups >= 2) {
      val broker = config.brokerInfo
      forAll(Gen.choose(broker.groupFrom, broker.groupUntil - 1)) { mainGroup =>
        val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

        val chainIndex = ChainIndex.unsafe(mainGroup, 0)
        val block11    = mine(blockFlow, chainIndex)
        val block12    = mine(blockFlow, chainIndex)
        blockFlow.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block11, 1)
        blockFlow.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block12, 1)

        val blockAdded = blockFlow.getBestDeps(chainIndex.from).getOutDep(chainIndex.to)
        if (blockAdded equals block12.hash) {
          blockAdded is block12.hash
          blockFlow.getPool(chainIndex).size is block11.transactions.length - 1
          val template = blockFlow.prepareBlockFlow(chainIndex).right.value
          template.transactions.length is block11.transactions.length - 1
        }
      }
    }
  }

  def mine(blockFlow: BlockFlow, chainIndex: ChainIndex, onlyTxForIntra: Boolean = false): Block = {
    val deps             = blockFlow.calBestDepsUnsafe(chainIndex.from).deps
    val (_, toPublicKey) = chainIndex.to.generateKey(PayTo.PKH)
    val coinbaseTx       = Transaction.coinbase(toPublicKey, ByteString.empty)
    val transactions = {
      if (config.brokerInfo.contains(chainIndex.from) && (chainIndex.isIntraGroup || !onlyTxForIntra)) {
        val mainGroup                  = chainIndex.from
        val (privateKey, publicKey, _) = genesisBalances(mainGroup.value)
        val balances                   = blockFlow.getUtxos(PayTo.PKH, publicKey).right.value
        val total                      = balances.sumBy(_._2.value)
        val (_, toPublicKey)           = chainIndex.to.generateKey(PayTo.PKH)
        val inputs                     = balances.map(_._1)
        val outputs = AVector(TxOutput.build(PayTo.PKH, 1, toPublicKey),
                              TxOutput.build(PayTo.PKH, total - 1, publicKey))
        val transferTx = Transaction.from(PayTo.PKH, inputs, outputs, publicKey, privateKey)
        AVector(transferTx, coinbaseTx)
      } else AVector(coinbaseTx)
    }

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, transactions, config.maxMiningTarget, nonce)
      if (Validation.validateMined(block, chainIndex)) block else iter(nonce + 1)
    }

    iter(0)
  }

  def addAndCheck(blockFlow: BlockFlow, block: Block, weightRatio: Int): Assertion = {
    blockFlow.add(block).isRight is true
    blockFlow.getWeight(block) isE config.maxMiningTarget * weightRatio
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

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader, weightFactor: Int): Assertion = {
    blockFlow.add(header).isRight is true
    blockFlow.getWeight(header) isE config.maxMiningTarget * weightFactor
  }

  def checkBalance(blockFlow: BlockFlow, groupIndex: Int, expected: BigInt): Assertion = {
    val address = genesisBalances(groupIndex)._2
    blockFlow.getUtxos(PayTo.PKH, address).right.value.sumBy(_._2.value) is expected
  }

  def show(blockFlow: BlockFlow): String = {
    blockFlow.getAllTips
      .map { tip =>
        val weight = blockFlow.getWeightUnsafe(tip)
        val header = blockFlow.getBlockHeaderUnsafe(tip)
        val index  = header.chainIndex
        val hash   = showHash(tip)
        val deps   = header.blockDeps.map(showHash).mkString("-")
        s"weight: $weight, from: ${index.from}, to: ${index.to} hash: $hash, deps: $deps"
      }
      .mkString("", "\n", "\n")
  }

  def showHash(hash: Hash): String = {
    Hex.toHexString(hash.bytes).take(8)
  }

  def getBalance(blockFlow: BlockFlow, address: ED25519PublicKey): BigInt = {
    val groupIndex = GroupIndex.from(PayTo.PKH, address)
    config.brokerInfo.contains(groupIndex) is true
    val query = blockFlow.getUtxos(PayTo.PKH, address)
    query.right.value.sumBy(_._2.value)
  }

  def showBalances(blockFlow: BlockFlow): Unit = {
    def show(txOutput: TxOutput): String = {
      txOutput.shortKey + ":" + txOutput.value
    }

    val address   = genesisBalances(config.brokerInfo.id)._2
    val txOutputs = blockFlow.getUtxos(PayTo.PKH, address).right.value.map(_._2)
    print(txOutputs.map(show).mkString("", ";", "\n"))
  }
}
