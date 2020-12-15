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
import scala.collection.mutable
import scala.language.implicitConversions

import akka.util.ByteString
import org.scalatest.{Assertion, BeforeAndAfterAll}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.flow.validation.{BlockValidation, HeaderValidation}
import org.alephium.protocol.{ALF, BlockHash, Hash, PrivateKey, PublicKey}
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.util._

// scalastyle:off number.of.methods
trait FlowFixture
    extends AlephiumSpec
    with AlephiumConfigFixture
    with StoragesFixture.Default
    with NumericHelpers {
  lazy val blockFlow: BlockFlow = genesisBlockFlow()

  lazy val keyManager: mutable.Map[LockupScript, PrivateKey] = mutable.Map.empty

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

  def tryToTransfer(blockFlow: BlockFlow,
                    chainIndex: ChainIndex,
                    amount: U256 = ALF.alf(1)): Block = {
    if (blockFlow.brokerConfig.contains(chainIndex.from)) {
      transfer(blockFlow, chainIndex, amount)
    } else {
      emptyBlock(blockFlow, chainIndex)
    }
  }

  def transferOnlyForIntraGroup(blockFlow: BlockFlow,
                                chainIndex: ChainIndex,
                                amount: U256 = ALF.alf(1)): Block = {
    if (chainIndex.isIntraGroup && blockFlow.brokerConfig.contains(chainIndex.from)) {
      transfer(blockFlow, chainIndex, amount)
    } else {
      emptyBlock(blockFlow, chainIndex)
    }
  }

  def emptyBlock(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    mine(blockFlow, chainIndex)((_, _) => AVector.empty[Transaction])
  }

  def simpleScript(blockFlow: BlockFlow,
                   chainIndex: ChainIndex,
                   txScript: StatefulScript): Block = {
    assume(blockFlow.brokerConfig.contains(chainIndex.from) && chainIndex.isIntraGroup)
    mine(blockFlow, chainIndex)(transferTxs(_, _, ALF.alf(1), 1, Some(txScript), true))
  }

  def simpleScriptMulti(blockFlow: BlockFlow,
                        chainIndex: ChainIndex,
                        invokers: AVector[LockupScript],
                        txScripts: AVector[StatefulScript]): Block = {
    assume(blockFlow.brokerConfig.contains(chainIndex.from) && chainIndex.isIntraGroup)
    val zipped = invokers.mapWithIndex {
      case (invoker, index) => invoker -> txScripts(index)
    }
    mine(blockFlow, chainIndex)(transferTxsMulti(_, _, zipped, ALF.alf(1) / 1000))
  }

  def transfer(blockFlow: BlockFlow,
               chainIndex: ChainIndex,
               amount: U256                   = ALF.alf(1),
               numReceivers: Int              = 1,
               gasFeeInTheAmount: Boolean     = true,
               lockTimeOpt: Option[TimeStamp] = None): Block = {
    assume(blockFlow.brokerConfig.contains(chainIndex.from))
    mine(blockFlow, chainIndex)(
      transferTxs(_, _, amount, numReceivers, None, gasFeeInTheAmount, lockTimeOpt))
  }

  def transferTxs(blockFlow: BlockFlow,
                  chainIndex: ChainIndex,
                  amount: U256,
                  numReceivers: Int,
                  txScriptOpt: Option[StatefulScript],
                  gasFeeInTheAmount: Boolean,
                  lockTimeOpt: Option[TimeStamp] = None): AVector[Transaction] = {
    val mainGroup                  = chainIndex.from
    val (privateKey, publicKey, _) = genesisKeys(mainGroup.value)
    val fromLockupScript           = LockupScript.p2pkh(publicKey)
    val unlockScript               = UnlockScript.p2pkh(publicKey)
    val balances                   = blockFlow.getUtxos(fromLockupScript).toOption.get
    val total                      = balances.fold(U256.Zero)(_ addUnsafe _._2.amount)
    val toLockupScripts = AVector.fill(numReceivers) {
      val (toPrivateKey, toPublicKey) = chainIndex.to.generateKey
      val lockupScript                = LockupScript.p2pkh(toPublicKey)
      keyManager += lockupScript -> toPrivateKey
      lockupScript
    }
    val inputs = balances.map(_._1).map(TxInput(_, unlockScript))

    val gasFee = defaultGasPrice * minimalGas.toU256
    val (outputs, remaining) = if (gasFeeInTheAmount) {
      val outputs   = toLockupScripts.map(TxOutput.asset(amount - gasFee, _, lockTimeOpt))
      val remaining = TxOutput.asset(total - amount * numReceivers, fromLockupScript)
      outputs -> remaining
    } else {
      val outputs = toLockupScripts.map(TxOutput.asset(amount, _, lockTimeOpt))
      val remaining =
        TxOutput.asset(total - amount * numReceivers - gasFee, fromLockupScript)
      outputs -> remaining
    }
    val unsignedTx = UnsignedTransaction(txScriptOpt, inputs, outputs :+ remaining)
    AVector(Transaction.from(unsignedTx, privateKey))
  }

  def transferTxsMulti(blockFlow: BlockFlow,
                       chainIndex: ChainIndex,
                       scripts: AVector[(LockupScript, StatefulScript)],
                       amount: U256): AVector[Transaction] = {
    scripts.map {
      case (lockupScript, txScript) =>
        transferTx(blockFlow, chainIndex, lockupScript, amount, Some(txScript))
    }
  }

  def transferTx(blockFlow: BlockFlow,
                 chainIndex: ChainIndex,
                 fromLockupScript: LockupScript,
                 amount: U256,
                 txScriptOpt: Option[StatefulScript]): Transaction = {
    val privateKey   = keyManager.getOrElse(fromLockupScript, genesisKeys(chainIndex.from.value)._1)
    val publicKey    = privateKey.publicKey
    val unlockScript = UnlockScript.p2pkh(publicKey)
    val balances     = blockFlow.getUtxos(fromLockupScript).toOption.get
    val total        = balances.fold(U256.Zero)(_ addUnsafe _._2.amount)

    val (toPrivateKey, toPublicKey) = chainIndex.to.generateKey
    val lockupScript                = LockupScript.p2pkh(toPublicKey)

    keyManager += lockupScript -> toPrivateKey
    val inputs     = balances.map(_._1).map(TxInput(_, unlockScript))
    val output     = TxOutput.asset(amount - defaultGasFee, lockupScript)
    val remaining  = TxOutput.asset(total - amount, fromLockupScript)
    val unsignedTx = UnsignedTransaction(txScriptOpt, inputs, AVector(output, remaining))
    Transaction.from(unsignedTx, privateKey)
  }

  def doubleSpendingTx(blockFlow: BlockFlow, chainIndex: ChainIndex): Transaction = {
    val mainGroup                  = chainIndex.from
    val (privateKey, publicKey, _) = genesisKeys(mainGroup.value)
    val fromLockupScript           = LockupScript.p2pkh(publicKey)
    val unlockScript               = UnlockScript.p2pkh(publicKey)

    val balances = {
      val balances = blockFlow.getUtxos(fromLockupScript).toOption.get
      balances ++ balances
    }
    balances.length is 2 // this function is used in this particular case

    val total  = balances.fold(U256.Zero)(_ addUnsafe _._2.amount)
    val amount = ALF.alf(1)

    val (_, toPublicKey) = chainIndex.to.generateKey
    val lockupScript     = LockupScript.p2pkh(toPublicKey)

    val inputs     = balances.map(_._1).map(TxInput(_, unlockScript))
    val output     = TxOutput.asset(amount - defaultGasFee, lockupScript)
    val remaining  = TxOutput.asset(total - amount, fromLockupScript)
    val unsignedTx = UnsignedTransaction(None, inputs, AVector(output, remaining))
    Transaction.from(unsignedTx, privateKey)
  }

  def payableCall(blockFlow: BlockFlow, chainIndex: ChainIndex, script: StatefulScript): Block = {
    mine(blockFlow, chainIndex)(payableCallTxs(_, _, script))
  }

  def payableCallTxs(blockFlow: BlockFlow,
                     chainIndex: ChainIndex,
                     script: StatefulScript): AVector[Transaction] = {
    assume(chainIndex.isIntraGroup && blockFlow.brokerConfig.contains(chainIndex.from))
    val mainGroup                  = chainIndex.from
    val (privateKey, publicKey, _) = genesisKeys(mainGroup.value)
    val fromLockupScript           = LockupScript.p2pkh(publicKey)
    val unlockScript               = UnlockScript.p2pkh(publicKey)
    val balances                   = blockFlow.getUtxos(fromLockupScript).toOption.get
    val inputs                     = balances.map(_._1).map(TxInput(_, unlockScript))

    val unsignedTx = UnsignedTransaction(Some(script), inputs, AVector.empty)
    val contractTx = TransactionTemplate.from(unsignedTx, privateKey)

    val (contractInputs, generateOutputs) =
      genInputsOutputs(blockFlow, mainGroup, contractTx, script)
    val fullTx = Transaction.from(unsignedTx, contractInputs, generateOutputs, privateKey)
    AVector(fullTx)
  }

  def mine(blockFlow: BlockFlow, chainIndex: ChainIndex)(
      prepareTxs: (BlockFlow, ChainIndex) => AVector[Transaction]): Block = {
    val deps             = blockFlow.calBestDepsUnsafe(chainIndex.from).deps
    val (_, toPublicKey) = chainIndex.to.generateKey
    val lockupScript     = LockupScript.p2pkh(toPublicKey)
    val txs              = prepareTxs(blockFlow, chainIndex)
    val blockTs          = TimeStamp.now()

    val coinbaseTx =
      Transaction.coinbase(chainIndex, txs, lockupScript, consensusConfig.maxMiningTarget, blockTs)

    mine(chainIndex, deps, txs :+ coinbaseTx, blockTs)
  }

  def mineWithoutCoinbase(chainIndex: ChainIndex,
                          txs: AVector[Transaction],
                          blockTs: TimeStamp): Block = {
    val deps             = blockFlow.calBestDepsUnsafe(chainIndex.from).deps
    val (_, toPublicKey) = chainIndex.to.generateKey
    val lockupScript     = LockupScript.p2pkh(toPublicKey)
    val coinbaseTx =
      Transaction.coinbase(chainIndex, txs, lockupScript, consensusConfig.maxMiningTarget, blockTs)

    mine(chainIndex, deps, txs :+ coinbaseTx, blockTs)
  }

  def mine(chainIndex: ChainIndex,
           deps: AVector[BlockHash],
           txs: AVector[Transaction],
           blockTs: TimeStamp,
           target: Target = consensusConfig.maxMiningTarget): Block = {
    val txsHash = Hash.hash(txs)
    Block(mineHeader(chainIndex, deps, txsHash, blockTs, target), txs)
  }

  def mineHeader(chainIndex: ChainIndex,
                 deps: AVector[BlockHash],
                 txsHash: Hash,
                 blockTs: TimeStamp,
                 target: Target = consensusConfig.maxMiningTarget): BlockHeader = {
    val blockDeps = BlockDeps.build(deps)

    @tailrec
    def iter(nonce: U256): BlockHeader = {
      val header = BlockHeader(blockDeps, txsHash, blockTs, target, nonce)
      if (PoW.checkMined(header, chainIndex)) header else iter(nonce.addOneUnsafe())
    }

    iter(0)
  }

  private def genInputsOutputs(
      blockFlow: BlockFlow,
      mainGroup: GroupIndex,
      tx: TransactionTemplate,
      txScript: StatefulScript): (AVector[ContractOutputRef], AVector[TxOutput]) = {
    val worldState = blockFlow.getBestCachedWorldState(mainGroup).toOption.get
    val result = StatefulVM
      .runTxScript(worldState, tx, txScript, tx.unsigned.startGas)
      .rightValue
    result.contractInputs -> result.generatedOutputs
  }

  def addAndCheck(blockFlow: BlockFlow, block: Block): Assertion = {
    val blockValidation =
      BlockValidation.build(blockFlow.brokerConfig, blockFlow.consensusConfig)
    blockValidation.validate(block, blockFlow).isRight is true
    blockFlow.add(block).isRight is true
  }

  def addAndCheck(blockFlow: BlockFlow, block: Block, weightRatio: Int): Assertion = {
    val blockValidation =
      BlockValidation.build(blockFlow.brokerConfig, blockFlow.consensusConfig)
    blockValidation.validate(block, blockFlow).isRight is true
    blockFlow.add(block).isRight is true
    blockFlow.getWeight(block) isE consensusConfig.maxMiningTarget * weightRatio
  }

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader, weightFactor: Int): Assertion = {
    val headerValidation = HeaderValidation.build(blockFlow.brokerConfig, blockFlow.consensusConfig)
    headerValidation.validate(header, blockFlow).isRight is true
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
        val deps   = header.blockDeps.deps.map(_.shortHex).mkString("-")
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
    val worldState    = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
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
