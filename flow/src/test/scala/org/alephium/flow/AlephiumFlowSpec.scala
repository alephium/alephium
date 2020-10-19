// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow

import scala.annotation.tailrec
import scala.language.implicitConversions

import akka.util.ByteString
import org.scalatest.{Assertion, BeforeAndAfterAll}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.flow.validation.Validation
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.util._

trait FlowFixture
    extends AlephiumSpec
    with AlephiumConfigFixture
    with StoragesFixture
    with NumericHelpers {
  lazy val blockFlow: BlockFlow = genesisBlockFlow()

  implicit def target2BigInt(target: Target): BigInt = BigInt(target.value)

  def genesisBlockFlow(): BlockFlow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
  def storageBlockFlow(): BlockFlow = BlockFlow.fromStorageUnsafe(storages, config.genesisBlocks)

  def isolatedBlockFlow(): BlockFlow = {
    val newStorages =
      StoragesFixture.buildStorages(rootPath.resolveSibling(Hash.generate.toHexString))
    BlockFlow.fromGenesisUnsafe(newStorages, config.genesisBlocks)
  }

  def getGenesisLockupScript(chainIndex: ChainIndex): LockupScript = {
    val mainGroup         = chainIndex.from
    val (_, publicKey, _) = genesisKeys(mainGroup.value)
    LockupScript.p2pkh(publicKey)
  }

  def minePayable(blockFlow: BlockFlow, group: GroupIndex, script: StatefulScript): Block = {
    val chainIndex       = ChainIndex(group, group)
    val deps             = blockFlow.calBestDepsUnsafe(chainIndex.from).deps
    val height           = blockFlow.getHashChain(chainIndex).maxHeight.toOption.get
    val (_, toPublicKey) = group.generateKey
    val coinbaseTx       = Transaction.coinbase(toPublicKey, height, Hash.generate.bytes)
    val transactions = {
      val mainGroup                  = chainIndex.from
      val (privateKey, publicKey, _) = genesisKeys(mainGroup.value)
      val fromLockupScript           = LockupScript.p2pkh(publicKey)
      val unlockScript               = UnlockScript.p2pkh(publicKey)
      val balances                   = blockFlow.getUtxos(fromLockupScript).toOption.get
      val inputs                     = balances.map(_._1).map(TxInput(_, unlockScript))

      val unsignedTx      = UnsignedTransaction(Some(script), inputs, AVector.empty)
      val contractTx      = TransactionTemplate.from(unsignedTx, privateKey)
      val generateOutputs = genOutputs(blockFlow, mainGroup, contractTx, script)
      val fullTx          = Transaction.from(unsignedTx, generateOutputs, privateKey)
      AVector(fullTx, coinbaseTx)
    }

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, transactions, consensusConfig.maxMiningTarget, nonce)
      if (Validation.validateMined(block, chainIndex)) block else iter(nonce + 1)
    }

    iter(0)
  }

  def mine(blockFlow: BlockFlow,
           chainIndex: ChainIndex,
           transfer: Boolean                      = true,
           onlyTxForIntra: Boolean                = false,
           txScriptOption: Option[StatefulScript] = None,
           callContract: Boolean                  = false): Block = {
    val deps             = blockFlow.calBestDepsUnsafe(chainIndex.from).deps
    val height           = blockFlow.getHashChain(chainIndex).maxHeight.toOption.get
    val (_, toPublicKey) = chainIndex.to.generateKey
    val coinbaseTx       = Transaction.coinbase(toPublicKey, height, Hash.generate.bytes)
    val transactions = {
      if (transfer && brokerConfig.contains(chainIndex.from) && (chainIndex.isIntraGroup || !onlyTxForIntra)) {
        val mainGroup                  = chainIndex.from
        val (privateKey, publicKey, _) = genesisKeys(mainGroup.value)
        val fromLockupScript           = LockupScript.p2pkh(publicKey)
        val unlockScript               = UnlockScript.p2pkh(publicKey)
        val balances                   = blockFlow.getUtxos(fromLockupScript).toOption.get
        val total                      = balances.fold(U256.Zero)(_ addUnsafe _._2.amount)
        val (_, toPublicKey)           = chainIndex.to.generateKey
        val toLockupScript             = LockupScript.p2pkh(toPublicKey)
        val inputs                     = balances.map(_._1).map(TxInput(_, unlockScript))

        val output0 = TxOutput.asset(1, height, toLockupScript)
        val output1 = TxOutput.asset(total - 1, height, fromLockupScript)
        if (callContract) {
          val unsignedTx      = UnsignedTransaction(txScriptOption, inputs, AVector(output1))
          val contractTx      = TransactionTemplate.from(unsignedTx, privateKey)
          val generateOutputs = genOutputs(blockFlow, mainGroup, contractTx, txScriptOption.get)
          val fullTx          = Transaction.from(unsignedTx, generateOutputs, privateKey)
          AVector(fullTx, coinbaseTx)
        } else {
          val unsignedTx = UnsignedTransaction(txScriptOption, inputs, AVector(output0, output1))
          val transferTx = Transaction.from(unsignedTx, privateKey)
          AVector(transferTx, coinbaseTx)
        }
      } else AVector(coinbaseTx)
    }

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, transactions, consensusConfig.maxMiningTarget, nonce)
      if (Validation.validateMined(block, chainIndex)) block else iter(nonce + 1)
    }

    iter(0)
  }

  private def genOutputs(blockFlow: BlockFlow,
                         mainGroup: GroupIndex,
                         tx: TransactionTemplate,
                         txScript: StatefulScript): AVector[TxOutput] = {
    val worldState = blockFlow.getBestCachedTrie(mainGroup).toOption.get
    StatefulVM.runTxScript(worldState, tx, txScript).toOption.get.generatedOutputs
  }

  def addAndCheck(blockFlow: BlockFlow, block: Block, weightRatio: Int): Assertion = {
    blockFlow.add(block).isRight is true
    blockFlow.getWeight(block) isE consensusConfig.maxMiningTarget * weightRatio
  }

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader, weightFactor: Int): Assertion = {
    blockFlow.add(header).isRight is true
    blockFlow.getWeight(header) isE consensusConfig.maxMiningTarget * weightFactor
  }

  def checkBalance(blockFlow: BlockFlow, groupIndex: Int, expected: U256): Assertion = {
    val address   = genesisKeys(groupIndex)._2
    val pubScript = LockupScript.p2pkh(address)
    blockFlow
      .getUtxos(pubScript)
      .toOption
      .get
      .sumBy(_._2.amount.v: BigInt) is expected.toBigInt
  }

  def checkBalance(blockFlow: BlockFlow, pubScript: LockupScript, expected: U256): Assertion = {
    blockFlow.getUtxos(pubScript).toOption.get.sumBy(_._2.amount.v: BigInt) is expected.v
  }

  def show(blockFlow: BlockFlow): String = {
    val tips = blockFlow.getAllTips
      .map { tip =>
        val weight = blockFlow.getWeightUnsafe(tip)
        val header = blockFlow.getBlockHeaderUnsafe(tip)
        val index  = header.chainIndex
        val deps   = header.blockDeps.map(_.shortHex).mkString("-")
        s"weight: $weight, from: ${index.from}, to: ${index.to} hash: ${tip.shortHex}, deps: $deps"
      }
      .mkString("", "\n", "\n")
    val bestDeps = (brokerConfig.groupFrom until brokerConfig.groupUntil)
      .map { group =>
        val bestDeps    = blockFlow.getBestDeps(GroupIndex.unsafe(group))
        val bestDepsStr = bestDeps.deps.map(_.shortHex).mkString("-")
        s"group $group, bestDeps: $bestDepsStr"
      }
      .mkString("", "\n", "\n")
    tips ++ bestDeps
  }

  def getBalance(blockFlow: BlockFlow, address: PublicKey): U256 = {
    val lockupScript = LockupScript.p2pkh(address)
    brokerConfig.contains(lockupScript.groupIndex) is true
    val query = blockFlow.getUtxos(lockupScript)
    U256.unsafe(query.toOption.get.sumBy(_._2.amount.v: BigInt).underlying())
  }

  def showBalances(blockFlow: BlockFlow): Unit = {
    def show(txOutput: TxOutput): String = {
      s"${txOutput.hint}:${txOutput.amount}"
    }

    val address   = genesisKeys(brokerConfig.brokerId)._2
    val pubScript = LockupScript.p2pkh(address)
    val txOutputs = blockFlow.getUtxos(pubScript).toOption.get.map(_._2)
    print(txOutputs.map(show).mkString("", ";", "\n"))
  }

  def checkState(blockFlow: BlockFlow,
                 chainIndex: ChainIndex,
                 key: Hash,
                 fields: AVector[Val],
                 outputRef: ContractOutputRef,
                 numAssets: Int    = 2,
                 numContracts: Int = 2): Assertion = {
    val worldState    = blockFlow.getBestPersistedTrie(chainIndex.from).fold(throw _, identity)
    val contractState = worldState.getContractState(key).fold(throw _, identity)

    contractState.fields is fields
    contractState.contractOutputRef is outputRef

    worldState.getAssetOutputs(ByteString.empty).toOption.get.length is numAssets
    worldState.getContractOutputs(ByteString.empty).toOption.get.length is numContracts
  }
}

trait AlephiumFlowSpec extends AlephiumSpec with BeforeAndAfterAll with FlowFixture {
  override def afterAll(): Unit = {
    cleanStorages()
  }
}

class AlephiumFlowActorSpec(name: String) extends AlephiumActorSpec(name) with AlephiumFlowSpec
