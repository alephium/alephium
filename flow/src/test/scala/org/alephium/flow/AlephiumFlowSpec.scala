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
import scala.util.Random

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.crypto.{Byte64, SecP256R1, SecP256R1PrivateKey}
import org.alephium.flow.core.{BlockFlow, ExtraUtxosInfo, FlowUtils}
import org.alephium.flow.core.FlowUtils.AssetOutputInfo
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.flow.validation.{BlockValidation, HeaderValidation, TxValidation}
import org.alephium.protocol._
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm._
import org.alephium.ralph.Compiler
import org.alephium.serde.{avectorSerde, deserialize, serialize}
import org.alephium.util._

// scalastyle:off number.of.methods file.size.limit
trait FlowFixture
    extends AlephiumSpec
    with RichBlockFlowT
    with AlephiumConfigFixture
    with StoragesFixture.Default
    with NumericHelpers {
  lazy val blockFlow: BlockFlow  = genesisBlockFlow()
  lazy val defaultUtxoLimit: Int = ALPH.MaxTxInputNum * 2

  lazy val keyManager: mutable.Map[LockupScript, PrivateKey] = mutable.Map.empty

  implicit def target2BigInt(target: Target): BigInt = BigInt(target.value)

  def genesisBlockFlow(): BlockFlow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
  def storageBlockFlow(): BlockFlow = BlockFlow.fromStorageUnsafe(config, storages)

  def isolatedBlockFlow(): BlockFlow = {
    val newStorages = StoragesFixture.buildStorages(rootPath.resolve(Hash.generate.toHexString))
    BlockFlow.fromGenesisUnsafe(newStorages, config.genesisBlocks)
  }

  def addWithoutViewUpdate(blockFlow: BlockFlow, block: Block): Assertion = {
    val worldState =
      Option.when(block.chainIndex.isIntraGroup)(
        blockFlow.getCachedWorldState(block.blockDeps, block.chainIndex.from).rightValue
      )
    blockFlow.add(block, worldState) isE ()
  }

  def addAndUpdateView(blockFlow: BlockFlow, block: Block): Assertion = {
    val worldState =
      blockFlow.getCachedWorldState(block.blockDeps, block.chainIndex.from).rightValue
    blockFlow.addAndUpdateView(block, Some(worldState)) isE ()
  }

  def getGenesisLockupScript(chainIndex: ChainIndex): LockupScript.Asset = {
    getGenesisLockupScript(chainIndex.from)
  }

  def getGenesisLockupScript(mainGroup: GroupIndex): LockupScript.Asset = {
    val (_, publicKey, _) = genesisKeys(mainGroup.value)
    LockupScript.p2pkh(publicKey)
  }

  def transferOnlyForIntraGroup(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      amount: U256 = ALPH.alph(1)
  ): Block = {
    if (chainIndex.isIntraGroup && blockFlow.brokerConfig.contains(chainIndex.from)) {
      transfer(blockFlow, chainIndex, amount)
    } else {
      emptyBlock(blockFlow, chainIndex)
    }
  }

  def emptyBlock(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    mineWithTxs(blockFlow, chainIndex)((_, _) => AVector.empty[Transaction])
  }

  def emptyBlock(blockFlow: BlockFlow, chainIndex: ChainIndex, timestamp: TimeStamp): Block = {
    val publicKey = chainIndex.to.generateKey._2
    val miner     = LockupScript.p2pkh(publicKey)
    mine(blockFlow, chainIndex, AVector.empty[Transaction], miner, Some(timestamp))
  }

  def emptyBlockWithMiner(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      miner: LockupScript.Asset
  ): Block = {
    mineWithTxsAndMiner(blockFlow, chainIndex, miner)((_, _) => AVector.empty[Transaction])
  }

  def simpleScript(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      txScript: StatefulScript,
      gas: Int = 100000,
      keyPairOpt: Option[(PrivateKey, PublicKey)] = None
  ): Block = {
    assume(blockFlow.brokerConfig.contains(chainIndex.from) && chainIndex.isIntraGroup)
    mineWithTxs(blockFlow, chainIndex)(
      transferTxs(
        _,
        _,
        ALPH.alph(1),
        1,
        Some(txScript),
        true,
        scriptGas = gas,
        keyPairOpt = keyPairOpt
      )
    )
  }

  def simpleScriptMulti(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      invokers: AVector[LockupScript.Asset],
      txScripts: AVector[StatefulScript]
  ): Block = {
    assume(blockFlow.brokerConfig.contains(chainIndex.from) && chainIndex.isIntraGroup)
    val zipped = invokers.mapWithIndex { case (invoker, index) =>
      invoker -> txScripts(index)
    }
    mineWithTxs(blockFlow, chainIndex)(transferTxsMulti(_, _, zipped, ALPH.alph(1) / 100))
  }

  // scalastyle:off method.length
  def prepareUtxos(
      fromPrivateKey: PrivateKey,
      fromPublicKey: PublicKey,
      outputsLimitOpt: Option[Int] = None
  ): (AVector[AssetOutputInfo], U256) = {
    lazy val lockupScript = Address.p2pkh(fromPublicKey).lockupScript
    lazy val initialUtxos = blockFlow
      .getUTXOs(lockupScript, Int.MaxValue, true)
      .rightValue
      .asUnsafe[AssetOutputInfo]
    def getBalance =
      blockFlow
        .getBalance(LockupScript.p2pkh(fromPublicKey), Int.MaxValue, false)
        .rightValue
        .totalAlph
    outputsLimitOpt match {
      case None => initialUtxos -> getBalance
      case Some(outputsLimit) =>
        require(outputsLimit < ALPH.MaxTxOutputNum, "Number of outputs must fit in a transaction")
        if (initialUtxos.length >= outputsLimit) {
          initialUtxos.take(outputsLimit) -> getBalance
        } else if (outputsLimit == 0) {
          AVector.empty[AssetOutputInfo] -> getBalance
        } else {
          require(outputsLimit > 0, "Number of outputs must be greater than 0")
          val amountPerOutput =
            getAlphBalance(blockFlow, lockupScript).divUnsafe(U256.unsafe(outputsLimit))
          val outputs = AVector.fill(outputsLimit - initialUtxos.length) {
            TxOutputInfo(lockupScript, amountPerOutput, AVector.empty, None)
          }
          val unsignedTx = blockFlow
            .transfer(
              fromPublicKey,
              outputs,
              None,
              nonCoinbaseMinGasPrice,
              Int.MaxValue,
              ExtraUtxosInfo.empty
            )
            .rightValue
            .rightValue
          val tx    = Transaction.from(unsignedTx, fromPrivateKey)
          val block = mineWithTxs(blockFlow, tx.chainIndex)((_, _) => AVector(tx))
          addAndCheck(blockFlow, block)
          val utxos = blockFlow
            .getUTXOs(lockupScript, Int.MaxValue, true)
            .rightValue
            .asUnsafe[AssetOutputInfo]
          assume(utxos.length == outputsLimit)
          utxos -> getBalance
        }
    }
  }
  // scalastyle:on method.length

  def transfer(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      blockTs: TimeStamp
  ): Block = {
    val (_, toPublicKey) = chainIndex.to.generateKey
    val miner            = LockupScript.p2pkh(toPublicKey)
    val txs =
      transferTxs(blockFlow, chainIndex, ALPH.alph(1), 1, None, true, None)
    mine(blockFlow, chainIndex, txs, miner, Some(blockTs))
  }
  def transfer(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      amount: U256 = ALPH.alph(1),
      numReceivers: Int = 1,
      gasFeeInTheAmount: Boolean = true,
      lockTimeOpt: Option[TimeStamp] = None
  ): Block = {
    assume(blockFlow.brokerConfig.contains(chainIndex.from))
    mineWithTxs(blockFlow, chainIndex)(
      transferTxs(_, _, amount, numReceivers, None, gasFeeInTheAmount, lockTimeOpt)
    )
  }
  def transfer(
      blockFlow: BlockFlow,
      from: PrivateKey,
      to: PublicKey,
      amount: U256
  ): Block = {
    transferWithGas(blockFlow, from, to, amount, nonCoinbaseMinGasPrice)
  }

  def transferWithGas(
      blockFlow: BlockFlow,
      from: PrivateKey,
      to: PublicKey,
      amount: U256,
      gasPrice: GasPrice
  ): Block = {
    transferWithGas(
      blockFlow,
      from,
      LockupScript.p2pkh(to),
      AVector.empty[(TokenId, U256)],
      amount,
      gasPrice
    )
  }

  def transfer(
      blockFlow: BlockFlow,
      from: PrivateKey,
      to: LockupScript.Asset,
      tokens: AVector[(TokenId, U256)],
      amount: U256
  ): Block = {
    transferWithGas(blockFlow, from, to, tokens, amount, nonCoinbaseMinGasPrice)
  }

  def transferWithGas(
      blockFlow: BlockFlow,
      from: PrivateKey,
      to: LockupScript.Asset,
      tokens: AVector[(TokenId, U256)],
      amount: U256,
      gasPrice: GasPrice
  ): Block = {
    val unsigned = blockFlow
      .transfer(
        from.publicKey,
        AVector(TxOutputInfo(to, amount, tokens, None)),
        None,
        gasPrice,
        defaultUtxoLimit,
        ExtraUtxosInfo.empty
      )
      .rightValue
      .rightValue
    val tx         = Transaction.from(unsigned, from)
    val chainIndex = tx.chainIndex
    mineWithTxs(blockFlow, chainIndex)((_, _) => AVector(tx))
  }

  // scalastyle:off parameter.number method.length
  def transferTxs(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      amount: U256,
      numReceivers: Int,
      txScriptOpt: Option[StatefulScript],
      gasFeeInTheAmount: Boolean,
      lockTimeOpt: Option[TimeStamp] = None,
      scriptGas: Int = 100000,
      validation: Boolean = true,
      keyPairOpt: Option[(PrivateKey, PublicKey)] = None
  ): AVector[Transaction] = {
    val mainGroup = chainIndex.from
    val (privateKey, publicKey) = keyPairOpt.getOrElse {
      val keys = genesisKeys(mainGroup.value)
      (keys._1, keys._2)
    }
    val gasAmount = txScriptOpt match {
      case None =>
        if (numReceivers > 1) {
          minimalGas addUnsafe defaultGasPerOutput.mulUnsafe(numReceivers)
        } else {
          minimalGas
        }
      case Some(_) => GasBox.unsafe(scriptGas)
    }
    val gasFee = nonCoinbaseMinGasPrice * gasAmount
    val outputAmount =
      if (gasFeeInTheAmount) amount - gasFee.divUnsafe(numReceivers) else amount
    val outputInfos = AVector.fill(numReceivers) {
      val (toPrivateKey, toPublicKey)      = chainIndex.to.generateKey
      val lockupScript: LockupScript.Asset = LockupScript.p2pkh(toPublicKey)
      keyManager += lockupScript -> toPrivateKey
      TxOutputInfo(lockupScript, outputAmount, AVector.empty, lockTimeOpt)
    }
    val unsignedTx =
      blockFlow
        .transfer(
          publicKey,
          outputInfos,
          Some(gasAmount),
          nonCoinbaseMinGasPrice,
          defaultUtxoLimit,
          ExtraUtxosInfo.empty
        )
        .rightValue
        .rightValue
    val newUnsignedTx = unsignedTx.copy(scriptOpt = txScriptOpt)
    val tx            = Transaction.from(newUnsignedTx, privateKey)

    if (validation) {
      val txValidation = TxValidation.build
      txValidation.validateMempoolTxTemplate(tx.toTemplate, blockFlow) isE ()
    }

    AVector(tx)
  }

  def transferTxsMulti(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      scripts: AVector[(LockupScript.Asset, StatefulScript)],
      amount: U256
  ): AVector[Transaction] = {
    scripts.map { case (lockupScript, txScript) =>
      transferTx(blockFlow, chainIndex, lockupScript, amount, Some(txScript))
    }
  }

  def transferTx(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      fromLockupScript: LockupScript,
      amount: U256,
      txScriptOpt: Option[StatefulScript]
  ): Transaction = {
    val privateKey = keyManager.getOrElse(fromLockupScript, genesisKeys(chainIndex.from.value)._1)
    val publicKey  = privateKey.publicKey

    val (toPrivateKey, toPublicKey) = chainIndex.to.generateKey
    val lockupScript                = LockupScript.p2pkh(toPublicKey)
    keyManager += lockupScript -> toPrivateKey

    val gasAmount = txScriptOpt match {
      case None    => minimalGas
      case Some(_) => GasBox.unsafe(100000)
    }

    val unsignedTx = blockFlow
      .transfer(
        publicKey,
        lockupScript,
        None,
        amount - nonCoinbaseMinGasFee,
        Some(gasAmount),
        nonCoinbaseMinGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .rightValue
    Transaction.from(unsignedTx.copy(scriptOpt = txScriptOpt), privateKey)
  }

  def doubleSpendingTx(blockFlow: BlockFlow, chainIndex: ChainIndex): Transaction = {
    val mainGroup                  = chainIndex.from
    val (privateKey, publicKey, _) = genesisKeys(mainGroup.value)
    val fromLockupScript           = LockupScript.p2pkh(publicKey)
    val unlockScript               = UnlockScript.p2pkh(publicKey)

    val balances = {
      val balances = blockFlow.getUsableUtxos(fromLockupScript, defaultUtxoLimit).rightValue
      balances ++ balances
    }
    balances.length is 2 // this function is used in this particular case

    val total  = balances.fold(U256.Zero)(_ addUnsafe _.output.amount)
    val amount = ALPH.alph(1)

    val (_, toPublicKey) = chainIndex.to.generateKey
    val lockupScript     = LockupScript.p2pkh(toPublicKey)

    val inputs     = balances.map(_.ref).map(TxInput(_, unlockScript))
    val output     = TxOutput.asset(amount - nonCoinbaseMinGasFee, lockupScript)
    val remaining  = TxOutput.asset(total - amount, fromLockupScript)
    val unsignedTx = UnsignedTransaction(None, inputs, AVector(output, remaining))
    Transaction.from(unsignedTx, privateKey)
  }

  def payableCall(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      script: StatefulScript,
      initialGas: Int = 200000,
      validation: Boolean = true,
      keyPairOpt: Option[(PrivateKey, PublicKey)] = None
  ): Block = {
    mineWithTxs(blockFlow, chainIndex)(
      payableCallTxs(_, _, script, initialGas, validation, keyPairOpt)
    )
  }

  def payableCallTxTemplate(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      fromLockupScript: LockupScript.Asset,
      script: StatefulScript,
      initialGas: Int,
      validation: Boolean
  ): TransactionTemplate = {
    assume(chainIndex.isIntraGroup && blockFlow.brokerConfig.contains(chainIndex.from))
    val privateKey   = keyManager.getOrElse(fromLockupScript, genesisKeys(chainIndex.from.value)._1)
    val publicKey    = privateKey.publicKey
    val unlockScript = UnlockScript.p2pkh(publicKey)
    val balances     = blockFlow.getUsableUtxos(fromLockupScript, defaultUtxoLimit).rightValue
    val inputs       = balances.map(_.ref).map(TxInput(_, unlockScript))

    val unsignedTx =
      UnsignedTransaction(Some(script), inputs, AVector.empty)
        .copy(gasAmount = GasBox.unsafe(initialGas))
    val contractTx = TransactionTemplate.from(unsignedTx, privateKey)

    if (validation) {
      val txValidation = TxValidation.build
      txValidation.validateMempoolTxTemplate(contractTx, blockFlow) isE ()
    }
    contractTx
  }

  def payableCallTxs(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      script: StatefulScript,
      initialGas: Int,
      validation: Boolean,
      keyPairOpt: Option[(PrivateKey, PublicKey)] = None
  ): AVector[Transaction] = {
    val mainGroup = chainIndex.from
    val (privateKey, publicKey) = keyPairOpt.getOrElse {
      val keys = genesisKeys(mainGroup.value)
      (keys._1, keys._2)
    }
    val fromLockupScript = LockupScript.p2pkh(publicKey)
    keyManager += fromLockupScript -> privateKey

    val contractTx =
      payableCallTxTemplate(blockFlow, chainIndex, fromLockupScript, script, initialGas, validation)

    val (contractInputs, generateOutputs) =
      genInputsOutputs(blockFlow, chainIndex.from, contractTx, script)
    val fullTx = Transaction.from(contractTx.unsigned, contractInputs, generateOutputs, privateKey)
    AVector(fullTx)
  }

  def mineFromMemPool(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    val miner         = getGenesisLockupScript(chainIndex.to)
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val block = mineWithTxs(blockFlow, chainIndex)((_, _) => blockTemplate.transactions.init)

    block.chainIndex is chainIndex

    block
  }

  def mineWithTxs(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      txs: AVector[Transaction]
  ): Block = {
    val block = mineWithTxs(blockFlow, chainIndex)((_, _) => txs)
    block.chainIndex is chainIndex
    block
  }

  def invalidNonceBlock(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    @tailrec
    def iter(current: Block): Block = {
      val tmp = Block(
        current.header.copy(nonce = Nonce.unsecureRandom()),
        current.transactions
      )
      if (!PoW.checkWork(tmp) && (tmp.chainIndex equals chainIndex)) tmp else iter(tmp)
    }
    iter(mineFromMemPool(blockFlow, chainIndex))
  }

  def mineWithTxs(blockFlow: BlockFlow, chainIndex: ChainIndex)(
      prepareTxs: (BlockFlow, ChainIndex) => AVector[Transaction]
  ): Block = {
    val (_, toPublicKey) = chainIndex.to.generateKey
    val lockupScript     = LockupScript.p2pkh(toPublicKey)
    mineWithTxsAndMiner(blockFlow, chainIndex, lockupScript)(prepareTxs)
  }

  def mineWithTxsAndMiner(blockFlow: BlockFlow, chainIndex: ChainIndex, miner: LockupScript.Asset)(
      prepareTxs: (BlockFlow, ChainIndex) => AVector[Transaction]
  ): Block = {
    val txs = prepareTxs(blockFlow, chainIndex)
    mine(blockFlow, chainIndex, txs, miner, None)
  }

  private def calcBlockDeps(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      timestamp: Option[TimeStamp]
  ): BlockDeps = {
    val hardFork = networkConfig.getHardFork(timestamp.getOrElse(TimeStamp.now()))
    if (hardFork.isDanubeEnabled()) {
      blockFlow.calBestFlowPerChainIndexUnsafe(chainIndex)
    } else {
      blockFlow.calBestDepsUnsafe(chainIndex.from)
    }
  }

  def mine(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      txs: AVector[Transaction],
      miner: LockupScript.Asset,
      timestamp: Option[TimeStamp]
  ): Block = {
    val deps = calcBlockDeps(blockFlow, chainIndex, timestamp)
    mine(blockFlow, chainIndex, deps, txs, miner, timestamp)
  }

  def mine(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      deps: BlockDeps
  ): Block = {
    val miner = getGenesisLockupScript(chainIndex.to)
    mine(blockFlow, chainIndex, deps, AVector.empty, miner, None)
  }

  def mine(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      deps: BlockDeps,
      txs: AVector[Transaction],
      miner: LockupScript.Asset,
      timestamp: Option[TimeStamp]
  ): Block = {
    val blockTs = timestamp.getOrElse {
      val parentTs = blockFlow.getBlockHeaderUnsafe(deps.parentHash(chainIndex)).timestamp
      FlowUtils.nextTimeStamp(parentTs)
    }
    val target = blockFlow.getNextHashTarget(chainIndex, deps, blockTs).rightValue
    val coinbaseTx =
      Transaction.powCoinbaseForTest(chainIndex, txs, miner, target, blockTs, AVector.empty)
    mine0(blockFlow, chainIndex, deps, txs :+ coinbaseTx, blockTs, target)
  }

  def mineWithoutCoinbase(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      txs: AVector[Transaction],
      blockTs: TimeStamp,
      uncles: AVector[SelectedGhostUncle] = AVector.empty
  ): Block = {
    val deps             = calcBlockDeps(blockFlow, chainIndex, Some(blockTs))
    val (_, toPublicKey) = chainIndex.to.generateKey
    val lockupScript     = LockupScript.p2pkh(toPublicKey)
    val consensusConfig  = consensusConfigs.getConsensusConfig(blockTs)
    val coinbaseTx =
      Transaction.powCoinbaseForTest(
        chainIndex,
        txs,
        lockupScript,
        consensusConfig.maxMiningTarget,
        blockTs,
        uncles
      )

    mine0(blockFlow, chainIndex, deps, txs :+ coinbaseTx, blockTs)
  }

  def mineBlockTemplate(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    val miner = getGenesisLockupScript(chainIndex.to)
    mine(blockFlow, blockFlow.prepareBlockFlowUnsafe(chainIndex, miner))
  }

  implicit class RichBlockFlowTemplate(template: BlockFlowTemplate) {
    def setGhostUncles(uncles: AVector[SelectedGhostUncle]): BlockFlowTemplate = {
      val txs   = template.transactions.init
      val miner = template.transactions.last.unsigned.fixedOutputs.head.lockupScript
      blockFlow.rebuild(template, txs, uncles, miner)
    }

    def setGhostUncles(blockFlow: BlockFlow, uncleHashes: AVector[BlockHash]): BlockFlowTemplate = {
      val height = template.height
      val ghostUncles = uncleHashes.map { hash =>
        val uncleBlock  = blockFlow.getBlockUnsafe(hash)
        val uncleHeight = blockFlow.getHeightUnsafe(hash)
        SelectedGhostUncle(hash, uncleBlock.minerLockupScript, height - uncleHeight)
      }
      setGhostUncles(ghostUncles)
    }

    lazy val ghostUncleHashes: AVector[BlockHash] = {
      val coinbase = template.transactions.last
      val hardFork = networkConfig.getHardFork(template.templateTs)
      CoinbaseData
        .deserialize(coinbase.unsigned.fixedOutputs.head.additionalData, hardFork)
        .rightValue
        .ghostUncleData
        .map(_.blockHash)
    }
  }

  def mine(blockFlow: BlockFlow, template: BlockFlowTemplate): Block = {
    mine(
      blockFlow,
      template.index,
      template.deps,
      template.transactions,
      template.templateTs,
      template.target
    )
  }

  def mine(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      deps: AVector[BlockHash],
      txs: AVector[Transaction],
      blockTs: TimeStamp,
      target: Target = consensusConfigs.maxAllowedMiningTarget
  ): Block = {
    mine0(blockFlow, chainIndex, BlockDeps.unsafe(deps), txs, blockTs, target)
  }

  def reMine(blockFlow: BlockFlow, chainIndex: ChainIndex, block: Block): Block = {
    mine0(
      blockFlow,
      chainIndex,
      block.blockDeps,
      block.transactions,
      block.timestamp,
      block.target
    )
  }

  def mine0(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      deps: BlockDeps,
      txs: AVector[Transaction],
      blockTs: TimeStamp,
      target: Target = consensusConfigs.maxAllowedMiningTarget
  ): Block = {
    val hardFork = networkConfig.getHardFork(blockTs)
    val loosenDeps =
      blockFlow.looseUncleDependencies(deps, chainIndex, TimeStamp.now(), hardFork).rightValue
    val depStateHash =
      blockFlow.getDepStateHash(loosenDeps, chainIndex.from).rightValue
    val txsHash = Block.calTxsHash(txs)
    Block(
      mineHeader(chainIndex, loosenDeps.deps, depStateHash, txsHash, blockTs, target),
      txs
    )
  }

  def mineHeader(
      chainIndex: ChainIndex,
      deps: AVector[BlockHash],
      depStateHash: Hash,
      txsHash: Hash,
      blockTs: TimeStamp,
      target: Target = consensusConfigs.maxAllowedMiningTarget
  ): BlockHeader = {
    val blockDeps = BlockDeps.build(deps)

    @tailrec
    def iter(nonce: U256): BlockHeader = {
      val header = BlockHeader.unsafe(
        blockDeps,
        depStateHash,
        txsHash,
        blockTs,
        target,
        Nonce.unsecureRandom()
      )
      if (PoW.checkMined(header, chainIndex)) header else iter(nonce.addOneUnsafe())
    }

    iter(0)
  }

  private def genInputsOutputs(
      blockFlow: BlockFlow,
      mainGroup: GroupIndex,
      tx: TransactionTemplate,
      txScript: StatefulScript
  ): (AVector[ContractOutputRef], AVector[TxOutput]) = {
    val groupView  = blockFlow.getMutableGroupView(mainGroup).rightValue
    val blockEnv   = blockFlow.getDryrunBlockEnv(tx.chainIndex).rightValue
    val preOutputs = groupView.getPreAssetOutputs(tx.unsigned.inputs).rightValue.get
    val result = StatefulVM
      .runTxScript(
        groupView.worldState.staging(),
        blockEnv,
        tx,
        preOutputs,
        txScript,
        tx.unsigned.gasAmount,
        0
      )
      .rightValue
    result.contractInputs -> result.generatedOutputs
  }

  def addAndCheck0(blockFlow: BlockFlow, block: Block): Unit = {
    val blockValidation = BlockValidation.build(blockFlow)
    val sideResult      = blockValidation.validate(block, blockFlow).rightValue
    blockFlow.addAndUpdateView(block, sideResult).rightValue
  }

  def addAndCheck(blockFlow: BlockFlow, blocks: Block*): Unit = {
    blocks.foreach { block =>
      addAndCheck0(blockFlow, block)
      checkOutputs(blockFlow, block)
    }
  }

  def addAndCheck(blockFlow: BlockFlow, block: Block, weightRatio: Int): Assertion = {
    addAndCheck0(blockFlow, block)
    val consensusConfig = consensusConfigs.getConsensusConfig(block.timestamp)
    blockFlow.getWeight(block) isE consensusConfig.minBlockWeight * weightRatio
  }

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader): Assertion = {
    val headerValidation = HeaderValidation.build(
      blockFlow.brokerConfig,
      blockFlow.consensusConfigs,
      blockFlow.networkConfig
    )
    headerValidation.validate(header, blockFlow).isRight is true
    blockFlow.addAndUpdateView(header).isRight is true
  }

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader, weightFactor: Int): Assertion = {
    addAndCheck(blockFlow, header)
    val consensusConfig = consensusConfigs.getConsensusConfig(header.timestamp)
    blockFlow.getWeight(header) isE consensusConfig.minBlockWeight * weightFactor
  }

  def checkBalance(blockFlow: BlockFlow, groupIndex: Int, expected: U256): Assertion = {
    val address   = genesisKeys(groupIndex)._2
    val pubScript = LockupScript.p2pkh(address)
    blockFlow
      .getUsableUtxos(pubScript, defaultUtxoLimit)
      .toOption
      .get
      .sumBy(_.output.amount.v: BigInt) is expected.toBigInt
  }

  def checkBalance(
      blockFlow: BlockFlow,
      pubScript: LockupScript.Asset,
      expected: U256
  ): Assertion = {
    blockFlow
      .getUsableUtxos(pubScript, defaultUtxoLimit)
      .rightValue
      .sumBy(_.output.amount.v: BigInt) is expected.v
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
    val bestDeps = brokerConfig.groupRange
      .map { group =>
        val bestDeps    = blockFlow.getBestDepsPreDanube(GroupIndex.unsafe(group))
        val bestDepsStr = bestDeps.deps.map(_.shortHex).mkString("-")
        s"group $group, bestDeps: $bestDepsStr"
      }
      .mkString("", "\n", "\n")
    tips ++ bestDeps
  }

  def getAlphBalance(blockFlow: BlockFlow, lockupScript: LockupScript.Asset): U256 = {
    brokerConfig.contains(lockupScript.groupIndex) is true
    val query = blockFlow.getUsableUtxos(lockupScript, defaultUtxoLimit)
    U256.unsafe(query.rightValue.sumBy(_.output.amount.v: BigInt).underlying())
  }

  def getTotalUtxoCountsAndBalance(
      blockFlow: BlockFlow,
      outs: AVector[TxOutputInfo]
  ): (Int, U256) = {
    outs.fold((0, U256.Zero)) { case ((utxoCount, balance), output) =>
      val balanceInfo =
        blockFlow.getBalance(output.lockupScript, Int.MaxValue, false).rightValue
      (utxoCount + balanceInfo.utxosNum, balance + balanceInfo.totalAlph)
    }
  }

  def showBalances(blockFlow: BlockFlow): Unit = {
    def show(txOutput: TxOutput): String = {
      s"${txOutput.hint}:${txOutput.amount}"
    }

    val address   = genesisKeys(brokerConfig.brokerId)._2
    val pubScript = LockupScript.p2pkh(address)
    val txOutputs = blockFlow.getUsableUtxos(pubScript, defaultUtxoLimit).rightValue.map(_.output)
    print(txOutputs.map(show).mkString("", ";", "\n"))
  }

  def getTokenBalance(
      blockFlow: BlockFlow,
      lockupScript: LockupScript.Asset,
      tokenId: TokenId
  ): U256 = {
    brokerConfig.contains(lockupScript.groupIndex) is true
    val utxos = blockFlow.getUsableUtxos(lockupScript, defaultUtxoLimit).rightValue
    utxos.fold(U256.Zero) { case (acc, utxo) =>
      val sum = utxo.output.tokens.fold(U256.Zero) { case (acc, (id, amount)) =>
        if (tokenId equals id) acc.addUnsafe(amount) else acc
      }
      acc.addUnsafe(sum)
    }
  }

  def checkState(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      contractId: ContractId,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      outputRef: ContractOutputRef,
      numAssets: Int = 2,
      numContracts: Int = 2
  ): Assertion = {
    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    val contractState = worldState.getContractState(contractId).fold(throw _, identity)

    contractState.immFields is immFields
    contractState.mutFields is mutFields
    contractState.contractOutputRef is outputRef

    worldState
      .getAssetOutputs(ByteString.empty, Int.MaxValue, false, (_, _) => true)
      .rightValue
      .length is numAssets
    worldState.getContractOutputs(ByteString.empty, Int.MaxValue).rightValue.length is numContracts
  }

  def checkOutputs(blockFlow: BlockFlow, block: Block): Unit = {
    val chainIndex = block.chainIndex
    if (chainIndex.isIntraGroup) {
      val worldState = blockFlow.getPersistedWorldState(block.hash).fold(throw _, identity)
      val hardFork   = networkConfig.getHardFork(block.timestamp)
      val usedRefs =
        block.nonCoinbase
          .flatMap(_.unsigned.inputs.map(_.outputRef))
          .toSet
          .asInstanceOf[Set[TxOutputRef]]
      block.nonCoinbase.foreach { tx =>
        tx.allOutputs.foreachWithIndex { case (output, index) =>
          val outputRef = TxOutputRef.from(output, TxOutputRef.key(tx.id, index))
          val exist = worldState.existOutput(outputRef).rightValue || (
            ALPH.isSequentialTxSupported(chainIndex, hardFork) && usedRefs.contains(outputRef)
          )
          exist is true
        }
      }
    }
  }

  def debugTxGas(blockFlow: BlockFlow, chainIndex: ChainIndex, tx0: Transaction): Unit = {
    val initialGas  = tx0.unsigned.gasAmount
    val worldState  = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
    val prevOutputs = worldState.getPreOutputs(tx0).rightValue
    val now         = TimeStamp.now()
    val blockEnv = BlockEnv(
      chainIndex,
      networkConfig.networkId,
      now,
      consensusConfigs.getConsensusConfig(now).maxMiningTarget,
      None
    )
    val txValidation = TxValidation.build
    val gasLeft =
      txValidation.checkGasAndWitnesses(tx0, prevOutputs, blockEnv, false, 0).rightValue
    val gasUsed = initialGas.use(gasLeft).rightValue
    print(s"length: ${tx0.unsigned.inputs.length}\n")
    print(s"gasUsed $gasUsed\n")
    import org.alephium.protocol.vm.GasSchedule._
    val estimate = txBaseGas addUnsafe
      txInputBaseGas.mulUnsafe(tx0.unsigned.inputs.length) addUnsafe
      txOutputBaseGas.mulUnsafe(tx0.outputsLength) addUnsafe
      GasBox.unsafe(2054 * tx0.unsigned.inputs.length)
    print(s"estimate: $estimate\n")
  }

  def contractCreation(
      code: StatefulContract,
      initialImmState: AVector[Val],
      initialMutState: AVector[Val],
      lockupScript: LockupScript.Asset,
      attoAlphAmount: U256,
      tokenIssuanceInfo: Option[TokenIssuance.Info] = None
  ): StatefulScript = {
    val address     = Address.Asset(lockupScript)
    val codeRaw     = Hex.toHexString(serialize(code))
    val immStateRaw = Hex.toHexString(serialize(initialImmState))
    val mutStateRaw = Hex.toHexString(serialize(initialMutState))
    val creation = tokenIssuanceInfo match {
      case Some(TokenIssuance.Info(amount, None)) =>
        s"createContractWithToken!{@$address -> ALPH: ${attoAlphAmount.v}}(#$codeRaw, #$immStateRaw, #$mutStateRaw, ${amount.v})"
      case Some(TokenIssuance.Info(amount, Some(transferTo))) => {
        val toAddress = Address.from(transferTo).toBase58
        s"createContractWithToken!{@$address -> ALPH: ${attoAlphAmount.v}}(#$codeRaw, #$immStateRaw, #$mutStateRaw, ${amount.v}, @${toAddress})"
      }
      case None =>
        s"createContract!{@$address -> ALPH: ${attoAlphAmount.v}}(#$codeRaw, #$immStateRaw, #$mutStateRaw)"
    }
    val scriptRaw =
      s"""
         |TxScript CreateContract {
         |  $creation
         |}
         |""".stripMargin
    Compiler.compileTxScript(scriptRaw).rightValue
  }

  lazy val outOfGasTxTemplate = {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val input =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val contract = Compiler.compileContract(input).rightValue
    val txScript =
      contractCreation(
        contract,
        AVector.empty,
        AVector.empty,
        getGenesisLockupScript(chainIndex),
        ALPH.alph(1)
      )
    payableCallTxTemplate(
      blockFlow,
      chainIndex,
      getGenesisLockupScript(chainIndex),
      txScript,
      33000,
      validation = false
    )
  }

  def createContract(
      input: String,
      initialImmState: AVector[Val] = AVector.empty,
      initialMutState: AVector[Val] = AVector.empty,
      tokenIssuanceInfo: Option[TokenIssuance.Info] = None,
      initialAttoAlphAmount: U256 = minimalAlphInContract,
      chainIndex: ChainIndex = ChainIndex.unsafe(0, 0)
  ): (ContractId, ContractOutputRef, Block) = {
    val contract = Compiler.compileContract(input).rightValue
    createCompiledContract(
      contract,
      initialImmState,
      initialMutState,
      tokenIssuanceInfo,
      initialAttoAlphAmount,
      chainIndex
    )
  }

  def createCompiledContract(
      contract: StatefulContract,
      initialImmState: AVector[Val] = AVector.empty,
      initialMutState: AVector[Val] = AVector.empty,
      tokenIssuanceInfo: Option[TokenIssuance.Info] = None,
      initialAttoAlphAmount: U256 = minimalAlphInContract,
      chainIndex: ChainIndex = ChainIndex.unsafe(0, 0)
  ): (ContractId, ContractOutputRef, Block) = {
    val genesisLockup = getGenesisLockupScript(chainIndex)
    val txScript =
      contractCreation(
        contract,
        initialImmState,
        initialMutState,
        genesisLockup,
        initialAttoAlphAmount,
        tokenIssuanceInfo
      )
    val block = payableCall(blockFlow, chainIndex, txScript)

    val contractOutputRef =
      TxOutputRef.unsafe(block.transactions.head, 0).asInstanceOf[ContractOutputRef]
    val contractId = ContractId.from(block.transactions.head.id, 0, chainIndex.from)
    val estimated  = contractId.inaccurateFirstOutputRef()
    estimated.hint is contractOutputRef.hint
    estimated.key.value.bytes.init is contractOutputRef.key.value.bytes.init

    deserialize[StatefulContract.HalfDecoded](serialize(contract.toHalfDecoded())).rightValue
      .toContract() isE contract
    addAndCheck(blockFlow, block)
    (contractId, contractOutputRef, block)
  }

  def callTxScript(
      input: String,
      chainIndex: ChainIndex = ChainIndex.unsafe(0, 0),
      keyPairOpt: Option[(PrivateKey, PublicKey)] = None
  ): Block = {
    val script = Compiler.compileTxScript(input).rightValue
    callCompiledTxScript(script, chainIndex, keyPairOpt)
  }

  def callCompiledTxScript(
      script: StatefulScript,
      chainIndex: ChainIndex = ChainIndex.unsafe(0, 0),
      keyPairOpt: Option[(PrivateKey, PublicKey)] = None
  ): Block = {
    script.toTemplateString() is Hex.toHexString(serialize(script))
    val block =
      if (script.entryMethod.usePreapprovedAssets) {
        payableCall(blockFlow, chainIndex, script, keyPairOpt = keyPairOpt)
      } else {
        simpleScript(blockFlow, chainIndex, script, keyPairOpt = keyPairOpt)
      }
    addAndCheck(blockFlow, block)
    block
  }

  def prepareRandomSequentialTxs(n: Int): AVector[Transaction] = {
    val tmpBlockFlow                  = isolatedBlockFlow()
    val startGroup                    = brokerConfig.randomGroupIndex()
    val (startPriKey, startPubKey, _) = genesisKeys(startGroup.value)
    var keys                          = AVector((startPriKey, startPubKey, ALPH.alph(1024)))
    def createTx(): Transaction = {
      val (fromPriKey, _, lastAmount) = keys.last
      val (toPriKey, toPubKey)        = brokerConfig.randomGroupIndex().generateKey
      val amount                      = lastAmount.subUnsafe(ALPH.oneAlph)
      val block                       = transfer(tmpBlockFlow, fromPriKey, toPubKey, amount)
      val chainIndex                  = block.chainIndex
      addAndCheck(tmpBlockFlow, block)
      if (!chainIndex.isIntraGroup) {
        // To confirm the transaction so its cross-chain output can be used
        addAndCheck(
          tmpBlockFlow,
          emptyBlock(tmpBlockFlow, ChainIndex(chainIndex.from, chainIndex.from))
        )
      }
      keys = keys :+ (toPriKey, toPubKey, amount)
      block.nonCoinbase.head
    }
    AVector.fill(n)(createTx())
  }

  def signWithWebAuthn(
      unsignedTx: UnsignedTransaction,
      priKey: SecP256R1PrivateKey
  ): (WebAuthn, Transaction) = {
    val bytes = bytesGen(WebAuthn.FlagIndex + 1).sample.get.toArray
    bytes(WebAuthn.FlagIndex) = (bytes(WebAuthn.FlagIndex) | 0x01).toByte
    val authenticatorData = ByteString.fromArrayUnsafe(bytes)
    val webauthn          = WebAuthn.createForTest(authenticatorData, WebAuthn.GET)
    val messageHash       = webauthn.messageHash(unsignedTx.id)
    val signature         = Byte64.from(SecP256R1.sign(messageHash, priKey))
    val inputSignatures   = webauthn.encodeForTest() :+ signature
    unsignedTx.scriptOpt match {
      case None =>
        (webauthn, Transaction.from(unsignedTx, inputSignatures))
      case Some(script) =>
        val txTemplate = TransactionTemplate(unsignedTx, inputSignatures, AVector.empty)
        val (contractInputs, generatedOutputs) =
          genInputsOutputs(blockFlow, unsignedTx.fromGroup, txTemplate, script)
        (
          webauthn,
          Transaction(
            unsignedTx,
            true,
            contractInputs,
            generatedOutputs,
            inputSignatures,
            AVector.empty
          )
        )
    }
  }

  def mineBlock(parentHash: BlockHash, block: Block, height: Int): Block = {
    val chainIndex   = block.chainIndex
    val lockupScript = getGenesisLockupScript(chainIndex)
    val template0    = BlockFlowTemplate.from(block, height)
    val parentIndex  = brokerConfig.groups - 1 + chainIndex.to.value
    val newDeps      = template0.deps.replace(parentIndex, parentHash)
    val template1 = blockFlow
      .rebuild(template0, template0.transactions.init, AVector.empty, lockupScript)
      .copy(
        deps = newDeps,
        depStateHash =
          blockFlow.getDepStateHash(BlockDeps.unsafe(newDeps), chainIndex.from).rightValue
      )
    mine(blockFlow, template1)
  }

  def mineBlockWithDep(chainIndex: ChainIndex, depHash: BlockHash): Block = {
    assume(blockFlow.containsUnsafe(depHash))
    val height        = blockFlow.getMaxHeightByWeight(chainIndex).rightValue
    val block         = emptyBlock(blockFlow, chainIndex)
    val template0     = BlockFlowTemplate.from(block, height)
    val depChainIndex = ChainIndex.from(depHash)
    val index         = template0.deps.indexWhere(ChainIndex.from(_) == depChainIndex)
    val template1     = template0.copy(deps = template0.deps.replace(index, depHash))
    mine(blockFlow, template1)
  }

  def mineTwoBlocksAndAdd(chainIndex: ChainIndex): (Block, Block) = {
    val blocks = Seq.fill(2)(emptyBlock(blockFlow, chainIndex))
    blocks.foreach(addAndCheck(blockFlow, _))
    val height = blockFlow.getBlockChain(chainIndex).maxHeightUnsafe
    val hashes = blockFlow.getHashes(chainIndex, height).rightValue
    hashes.length is 2
    (blockFlow.getBlockUnsafe(hashes(0)), blockFlow.getBlockUnsafe(hashes(1)))
  }
}

trait GhostUncleFixture extends FlowFixture {
  override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

  private def getBlockTemplate(blockFlow: BlockFlow, chainIndex: ChainIndex, height: Int) = {
    val hash           = blockFlow.getHashes(chainIndex, height).rightValue.head
    val mainChainBlock = blockFlow.getBlockUnsafe(hash)
    BlockFlowTemplate.from(mainChainBlock, height)
  }

  private def mineDuplicateGhostUncle(blockFlow: BlockFlow, template: BlockFlowTemplate) = {
    val block = mine(blockFlow, template)
    BlockHeader.fromSameTemplate(block.header, template.dummyHeader()) is true
    addAndCheck(blockFlow, block)
    block
  }

  def mineBlocks(blockFlow: BlockFlow, chainIndex: ChainIndex, size: Int): AVector[Block] = {
    val depGroupIndex = (chainIndex.from.value + 1) % blockFlow.brokerConfig.groups
    val depChainIndex = ChainIndex.unsafe(depGroupIndex, depGroupIndex)
    AVector.from(0 until size).flatMap { _ =>
      val block0 = emptyBlock(blockFlow, depChainIndex)
      addAndCheck(blockFlow, block0)
      val block1 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block1)
      AVector(block0, block1)
    }
  }

  def mineUncleBlocks(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      uncleSize: Int
  ): AVector[BlockHash] = {
    mineBlocks(blockFlow, chainIndex, ALPH.MaxGhostUncleAge)
    val height = blockFlow.getMaxHeightByWeight(chainIndex).rightValue
    AVector.from(0 until uncleSize).map { index =>
      mineValidGhostUncleBlockAt(blockFlow, chainIndex, height - 1 - index).hash
    }
  }

  def mineDuplicateGhostUncleBlockAt(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      height: Int
  ): Block = {
    val template = getBlockTemplate(blockFlow, chainIndex, height)
    mineDuplicateGhostUncle(blockFlow, template)
  }

  def mineDuplicateGhostUncleBlock(blockFlow: BlockFlow, templateBlock: Block): Block = {
    val height   = blockFlow.getHeightUnsafe(templateBlock.hash)
    val template = BlockFlowTemplate.from(templateBlock, height)
    mineDuplicateGhostUncle(blockFlow, template)
  }

  def mineValidGhostUncleBlockAt(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      height: Int
  ): Block = {
    val sameBlockDeps = Random.nextBoolean()
    val template      = getBlockTemplate(blockFlow, chainIndex, height)
    val block = if (sameBlockDeps) {
      val miner = getGenesisLockupScript(chainIndex.to)
      val txs   = transferTxs(blockFlow, chainIndex, ALPH.oneAlph, 1, None, true)
      mine(blockFlow, chainIndex, BlockDeps.unsafe(template.deps), txs, miner, None)
    } else {
      val depGroupIndex = (chainIndex.from.value + 1) % blockFlow.brokerConfig.groups
      val depIndex = if (depGroupIndex < chainIndex.from.value) depGroupIndex else depGroupIndex - 1
      val oldDep   = blockFlow.getBlockHeaderUnsafe(template.deps(depIndex))
      val newDeps  = BlockDeps.unsafe(template.deps.replace(depIndex, oldDep.parentHash))
      mine(blockFlow, chainIndex, newDeps)
    }
    BlockHeader.fromSameTemplate(block.header, template.dummyHeader()) is false
    addAndCheck(blockFlow, block)
    block
  }

  def mineTwoGhostUnclesAt(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      height: Int
  ): (Block, Block) = {
    val duplicateGhostUncle = mineDuplicateGhostUncleBlockAt(blockFlow, chainIndex, height)
    val validGhostUncle     = mineValidGhostUncleBlockAt(blockFlow, chainIndex, height)
    (duplicateGhostUncle, validGhostUncle)
  }
}

trait AlephiumFlowSpec extends AlephiumSpec with FlowFixture

class AlephiumFlowActorSpec extends AlephiumActorSpec with AlephiumFlowSpec
