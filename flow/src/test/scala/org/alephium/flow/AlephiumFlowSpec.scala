package org.alephium.flow

import scala.annotation.tailrec

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

  def genesisBlockFlow(): BlockFlow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
  def storageBlockFlow(): BlockFlow = BlockFlow.fromStorageUnsafe(storages, config.genesisBlocks)

  def isolatedBlockFlow(): BlockFlow = {
    val newStorages =
      StoragesFixture.buildStorages(rootPath.resolveSibling(Hash.generate.toHexString))
    BlockFlow.fromGenesisUnsafe(newStorages, config.genesisBlocks)
  }

  def mine(blockFlow: BlockFlow,
           chainIndex: ChainIndex,
           onlyTxForIntra: Boolean                                      = false,
           outputScriptOption: Option[(StatefulContract, AVector[Val])] = None,
           txScriptOption: Option[StatefulScript]                       = None): Block = {
    val deps             = blockFlow.calBestDepsUnsafe(chainIndex.from).deps
    val height           = blockFlow.getHashChain(chainIndex).maxHeight.toOption.get
    val (_, toPublicKey) = chainIndex.to.generateKey
    val coinbaseTx       = Transaction.coinbase(toPublicKey, height, Hash.generate.bytes)
    val transactions = {
      if (brokerConfig.contains(chainIndex.from) && (chainIndex.isIntraGroup || !onlyTxForIntra)) {
        val mainGroup                  = chainIndex.from
        val (privateKey, publicKey, _) = genesisKeys(mainGroup.value)
        val fromLockupScript           = LockupScript.p2pkh(publicKey)
        val unlockScript               = UnlockScript.p2pkh(publicKey)
        val balances                   = blockFlow.getUtxos(fromLockupScript).toOption.get
        val total                      = balances.fold(U64.Zero)(_ addUnsafe _._2.amount)
        val (_, toPublicKey)           = chainIndex.to.generateKey
        val toLockupScript             = LockupScript.p2pkh(toPublicKey)
        val inputs                     = balances.map(_._1).map(TxInput(_, unlockScript))

        val output0 = outputScriptOption match {
          case Some((script, _)) => TxOutput.contract(1, height, toLockupScript, script)
          case None              => TxOutput.asset(1, height, toLockupScript)
        }
        val output1 = TxOutput.asset(total - 1, height, fromLockupScript)
        val outputs = AVector[TxOutput](output0, output1)
        val unsignedTx = outputScriptOption match {
          case Some((_, state)) =>
            UnsignedTransaction(txScriptOption, inputs, outputs, AVector(state))
          case None =>
            UnsignedTransaction(txScriptOption, inputs, outputs, AVector.empty)
        }
        val transferTx = Transaction.from(unsignedTx, privateKey)
        AVector(transferTx, coinbaseTx)
      } else AVector(coinbaseTx)
    }

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, transactions, consensusConfig.maxMiningTarget, nonce)
      if (Validation.validateMined(block, chainIndex)) block else iter(nonce + 1)
    }

    iter(0)
  }

  def addAndCheck(blockFlow: BlockFlow, block: Block, weightRatio: Int): Assertion = {
    blockFlow.add(block).isRight is true
    blockFlow.getWeight(block) isE consensusConfig.maxMiningTarget * weightRatio
  }

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader, weightFactor: Int): Assertion = {
    blockFlow.add(header).isRight is true
    blockFlow.getWeight(header) isE consensusConfig.maxMiningTarget * weightFactor
  }

  def checkBalance(blockFlow: BlockFlow, groupIndex: Int, expected: U64): Assertion = {
    val address   = genesisBalances(groupIndex)._2
    val pubScript = LockupScript.p2pkh(address)
    blockFlow
      .getUtxos(pubScript)
      .toOption
      .get
      .sumBy(_._2.amount.v) is expected.v
  }

  def checkBalance(blockFlow: BlockFlow, pubScript: LockupScript, expected: U64): Assertion = {
    blockFlow.getUtxos(pubScript).toOption.get.sumBy(_._2.amount.v) is expected.v
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

  def getBalance(blockFlow: BlockFlow, address: PublicKey): U64 = {
    val lockupScript = LockupScript.p2pkh(address)
    brokerConfig.contains(lockupScript.groupIndex) is true
    val query = blockFlow.getUtxos(lockupScript)
    query.toOption.get.sumBy(_._2.amount.v)
  }

  def showBalances(blockFlow: BlockFlow): Unit = {
    def show(txOutput: TxOutput): String = {
      s"${txOutput.scriptHint}:${txOutput.amount}"
    }

    val address   = genesisBalances(brokerConfig.brokerId)._2
    val pubScript = LockupScript.p2pkh(address)
    val txOutputs = blockFlow.getUtxos(pubScript).toOption.get.map(_._2)
    print(txOutputs.map(show).mkString("", ";", "\n"))
  }
}

trait AlephiumFlowSpec extends AlephiumSpec with BeforeAndAfterAll with FlowFixture {
  override def afterAll(): Unit = {
    cleanStorages()
  }
}

class AlephiumFlowActorSpec(name: String) extends AlephiumActorSpec(name) with AlephiumFlowSpec
