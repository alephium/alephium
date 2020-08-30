package org.alephium.flow

import scala.annotation.tailrec

import org.scalatest.{Assertion, BeforeAndAfterAll}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.flow.validation.Validation
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, StatefulContract, StatefulScript, UnlockScript, Val}
import org.alephium.util.{AlephiumActorSpec, AlephiumSpec, AVector, NumericHelpers, U64}

trait AlephiumFlowSpec
    extends AlephiumSpec
    with AlephiumConfigFixture
    with StoragesFixture
    with BeforeAndAfterAll
    with NumericHelpers {
  lazy val blockFlow: BlockFlow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)

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
        val (privateKey, publicKey, _) = genesisBalances(mainGroup.value)
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

  override def afterAll(): Unit = {
    cleanStorages()
  }
}

class AlephiumFlowActorSpec(name: String) extends AlephiumActorSpec(name) with AlephiumFlowSpec
