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

package org.alephium.flow.core

import scala.annotation.tailrec
import scala.reflect.ClassTag

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.Utils
import org.alephium.flow.mempool._
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.flow.setting.{ConsensusSetting, MemPoolSetting}
import org.alephium.io.{IOError, IOResult, IOUtils}
import org.alephium.protocol.BlockHash
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.util.{AVector, TimeStamp, U256}

trait FlowUtils
    extends MultiChain
    with BlockFlowState
    with SyncUtils
    with TxUtils
    with ConflictedBlocks
    with StrictLogging {
  implicit def mempoolSetting: MemPoolSetting
  implicit def consensusConfig: ConsensusSetting

  val grandPool = GrandPool.empty

  def getMemPool(mainGroup: GroupIndex): MemPool = {
    grandPool.getMemPool(mainGroup)
  }

  def getMemPool(chainIndex: ChainIndex): MemPool = {
    grandPool.getMemPool(chainIndex)
  }

  def calMemPoolChangesUnsafe(
      mainGroup: GroupIndex,
      oldDeps: BlockDeps,
      newDeps: BlockDeps
  ): MemPoolChanges = {
    val oldOutDeps = oldDeps.outDeps
    val newOutDeps = newDeps.outDeps
    val diffs = AVector.tabulate(brokerConfig.groups) { toGroup =>
      val toGroupIndex = GroupIndex.unsafe(toGroup)
      val oldDep       = oldOutDeps(toGroup)
      val newDep       = newOutDeps(toGroup)
      val index        = ChainIndex(mainGroup, toGroupIndex)
      getBlockChain(index).calBlockDiffUnsafe(newDep, oldDep)
    }
    val toRemove = diffs.map(_.toAdd.flatMap(_.nonCoinbase))
    val toAdd    = diffs.map(_.toRemove.flatMap(_.nonCoinbase))
    if (toAdd.sumBy(_.length) == 0) Normal(toRemove) else Reorg(toRemove, toAdd)
  }

  def updateGrandPoolUnsafe(
      mainGroup: GroupIndex,
      newDeps: BlockDeps,
      oldDeps: BlockDeps,
      maxHeightGap: Int
  ): AVector[TransactionTemplate] = {
    val newHeight = getHeightUnsafe(newDeps.uncleHash(mainGroup))
    val oldHeight = getHeightUnsafe(oldDeps.uncleHash(mainGroup))
    if (newHeight <= oldHeight + maxHeightGap) {
      updateMemPoolUnsafe(mainGroup, newDeps, oldDeps)
      getMemPool(mainGroup).updatePendingPool()
    } else { // we don't update tx pool when the node is syncing
      AVector.empty
    }
  }

  def updateGrandPoolUnsafe(
      mainGroup: GroupIndex,
      newDeps: BlockDeps,
      oldDeps: BlockDeps
  ): AVector[TransactionTemplate] = {
    updateGrandPoolUnsafe(mainGroup, newDeps, oldDeps, maxSyncBlocksPerChain)
  }

  def updateMemPoolUnsafe(mainGroup: GroupIndex, newDeps: BlockDeps, oldDeps: BlockDeps): Unit = {
    calMemPoolChangesUnsafe(mainGroup, oldDeps, newDeps) match {
      case Normal(toRemove) =>
        val removed = toRemove.foldWithIndex(0) { (sum, txs, toGroup) =>
          val toGroupIndex = GroupIndex.unsafe(toGroup)
          val index        = ChainIndex(mainGroup, toGroupIndex)
          sum + getMemPool(mainGroup).removeFromTxPool(index, txs.map(_.toTemplate))
        }
        if (removed > 0) {
          logger.debug(s"Normal update for #$mainGroup mempool: #$removed removed")
        }
      case Reorg(toRemove, toAdd) =>
        val (removed, added) = getMemPool(mainGroup).reorg(toRemove, toAdd)
        logger.info(s"Reorg for #$mainGroup mempool: #$removed removed, #$added added")
    }
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps

  def updateBestDeps(): IOResult[AVector[TransactionTemplate]]

  def updateBestDepsUnsafe(): AVector[TransactionTemplate]

  def calBestDepsUnsafe(group: GroupIndex): BlockDeps

  def collectPooledTxs(chainIndex: ChainIndex): AVector[TransactionTemplate] = {
    getMemPool(chainIndex).collectForBlock(chainIndex, mempoolSetting.txMaxNumberPerBlock)
  }

  def filterValidInputsUnsafe(
      txs: AVector[TransactionTemplate],
      groupView: BlockFlowGroupView[WorldState.Cached]
  ): AVector[TransactionTemplate] = {
    txs.filter { tx =>
      Utils.unsafe(groupView.getPreOutputs(tx.unsigned.inputs)).nonEmpty
    }
  }

  // TODO: truncate txs in advance for efficiency
  def collectTransactions(
      chainIndex: ChainIndex,
      groupView: BlockFlowGroupView[WorldState.Cached],
      bestDeps: BlockDeps
  ): IOResult[AVector[TransactionTemplate]] = {
    IOUtils.tryExecute {
      val candidates0 = collectPooledTxs(chainIndex)
      val candidates1 = FlowUtils.filterDoubleSpending(candidates0)
      // some tx inputs might from bestDeps, but not loosenDeps
      val candidates2 = filterValidInputsUnsafe(candidates1, groupView)
      // we don't want any tx that conflicts with bestDeps
      val candidates3 = filterConflicts(chainIndex.from, bestDeps, candidates2, getBlockUnsafe)
      FlowUtils.truncateTxs(candidates3, maximalTxsInOneBlock, maximalGas)
    }
  }

  // all the inputs and double spending should have been checked
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def executeTxTemplates(
      chainIndex: ChainIndex,
      blockEnv: BlockEnv,
      deps: BlockDeps,
      groupView: BlockFlowGroupView[WorldState.Cached],
      txTemplates: AVector[TransactionTemplate]
  ): IOResult[AVector[Transaction]] = {
    if (chainIndex.isIntraGroup) {
      val parentHash = deps.getOutDep(chainIndex.to)
      val order      = Block.getScriptExecutionOrder(parentHash, txTemplates)
      val fullTxs =
        Array.ofDim[Transaction](txTemplates.length + 1) // reverse 1 slot for coinbase tx
      txTemplates.foreachWithIndex { case (tx, index) =>
        if (tx.unsigned.scriptOpt.isEmpty) {
          fullTxs(index) = FlowUtils.convertNonScriptTx(tx)
        }
      }

      order
        .foreachE[IOError] { scriptTxIndex =>
          val tx = txTemplates(scriptTxIndex)
          generateFullTx(groupView, blockEnv, tx, tx.unsigned.scriptOpt.get)
            .map(fullTx => fullTxs(scriptTxIndex) = fullTx)
        }
        .map { _ =>
          AVector.unsafe(fullTxs, 0, txTemplates.length)
        }
    } else {
      Right(txTemplates.map(FlowUtils.convertNonScriptTx))
    }
  }

  def prepareBlockFlow(
      chainIndex: ChainIndex,
      miner: LockupScript.Asset
  ): IOResult[BlockFlowTemplate] = {
    assume(brokerConfig.contains(chainIndex.from))
    val singleChain = getBlockChain(chainIndex)
    val bestDeps    = getBestDeps(chainIndex.from)
    for {
      target       <- singleChain.getHashTarget(bestDeps.getOutDep(chainIndex.to))
      parentHeader <- getBlockHeader(bestDeps.parentHash(chainIndex))
      templateTs = FlowUtils.nextTimeStamp(parentHeader.timestamp)
      loosenDeps <- looseUncleDependencies(bestDeps, chainIndex, templateTs)
      groupView  <- getMutableGroupView(chainIndex.from, loosenDeps)
      candidates <- collectTransactions(chainIndex, groupView, bestDeps)
      template <- prepareBlockFlowUnsafe(
        chainIndex,
        loosenDeps,
        groupView,
        candidates,
        target,
        templateTs,
        miner
      )
    } yield template
  }

  def prepareBlockFlowUnsafe(
      chainIndex: ChainIndex,
      loosenDeps: BlockDeps,
      groupView: BlockFlowGroupView[WorldState.Cached],
      candidates: AVector[TransactionTemplate],
      target: Target,
      templateTs: TimeStamp,
      miner: LockupScript.Asset
  ): IOResult[BlockFlowTemplate] = {
    val blockEnv = BlockEnv(templateTs, target)
    for {
      fullTxs      <- executeTxTemplates(chainIndex, blockEnv, loosenDeps, groupView, candidates)
      depStateHash <- getDepStateHash(loosenDeps, chainIndex.from)
    } yield {
      val coinbaseTx =
        Transaction.coinbase(chainIndex, fullTxs, miner, target, templateTs)
      BlockFlowTemplate(
        chainIndex,
        loosenDeps.deps,
        depStateHash,
        target,
        templateTs,
        fullTxs :+ coinbaseTx
      )
    }
  }

  def looseUncleDependencies(
      bestDeps: BlockDeps,
      chainIndex: ChainIndex,
      currentTs: TimeStamp
  ): IOResult[BlockDeps] = {
    val thresholdTs = currentTs.minusUnsafe(consensusConfig.uncleDependencyGapTime)
    bestDeps.deps
      .mapWithIndexE {
        case (hash, k) if k != (groups - 1 + chainIndex.to.value) =>
          val hashIndex = ChainIndex.from(hash)
          val chain     = getHeaderChain(hashIndex)
          looseDependency(hash, chain, thresholdTs)
        case (hash, _) => Right(hash)
      }
      .map(BlockDeps.unsafe)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def looseDependency(
      hash: BlockHash,
      headerChain: BlockHeaderChain,
      thresholdTs: TimeStamp
  ): IOResult[BlockHash] = {
    headerChain.getTimestamp(hash).flatMap {
      case timeStamp if timeStamp <= thresholdTs =>
        Right(hash)
      case _ =>
        headerChain.getParentHash(hash).flatMap(looseDependency(_, headerChain, thresholdTs))
    }
  }

  def prepareBlockFlowUnsafe(
      chainIndex: ChainIndex,
      miner: LockupScript.Asset
  ): BlockFlowTemplate = {
    Utils.unsafe(prepareBlockFlow(chainIndex, miner))
  }

  def generateFullTx(
      groupView: BlockFlowGroupView[WorldState.Cached],
      blockEnv: BlockEnv,
      tx: TransactionTemplate,
      script: StatefulScript
  ): IOResult[Transaction] = {
    for {
      preOutputs <- groupView
        .getPreOutputs(tx.unsigned.inputs)
        .flatMap {
          case None =>
            Left(IOError.Other(new RuntimeException(s"Inputs should exist, but not actually")))
          case Some(outputs) => Right(outputs)
        }
    } yield {
      StatefulVM.dryrunTxScript(
        groupView.worldState,
        blockEnv,
        tx,
        preOutputs,
        script,
        tx.unsigned.startGas
      ) match {
        case Left(_)       => FlowUtils.convertFailedScriptTx(preOutputs, tx)
        case Right(result) => FlowUtils.convertSuccessfulTx(tx, result)
      }
    }
  }
}

object FlowUtils {
  final case class AssetOutputInfo(ref: AssetOutputRef, output: AssetOutput, outputType: OutputType)

  sealed trait OutputType {
    def cachedLevel: Int
  }
  case object PersistedOutput extends OutputType {
    val cachedLevel = 0
  }
  case object UnpersistedBlockOutput extends OutputType {
    val cachedLevel = 1
  }
  case object SharedPoolOutput extends OutputType {
    val cachedLevel = 2
  }
  case object PendingPoolOutput extends OutputType {
    val cachedLevel = 3
  }

  def filterDoubleSpending[T <: TransactionAbstract: ClassTag](txs: AVector[T]): AVector[T] = {
    var output   = AVector.ofSize[T](txs.length)
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
    txs.foreach { tx =>
      if (tx.unsigned.inputs.forall(input => !utxoUsed.contains(input.outputRef))) {
        utxoUsed.addAll(tx.unsigned.inputs.toIterable.view.map(_.outputRef))
        output = output :+ tx
      }
    }
    output
  }

  def convertNonScriptTx(txTemplate: TransactionTemplate): Transaction = {
    Transaction(
      txTemplate.unsigned,
      AVector.empty,
      AVector.empty,
      txTemplate.inputSignatures,
      txTemplate.contractSignatures
    )
  }

  def convertSuccessfulTx(
      txTemplate: TransactionTemplate,
      result: TxScriptExecution
  ): Transaction = {
    Transaction(
      txTemplate.unsigned,
      result.contractInputs,
      result.generatedOutputs,
      txTemplate.inputSignatures,
      txTemplate.contractSignatures
    )
  }

  def deductGas(inputs: AVector[TxOutput], gasFee: U256): AVector[TxOutput] = {
    inputs.replace(0, inputs(0).payGasUnsafe(gasFee))
  }

  def convertFailedScriptTx(
      preOutputs: AVector[TxOutput],
      txTemplate: TransactionTemplate
  ): Transaction = {
    val remainingBalances = deductGas(preOutputs, txTemplate.gasFeeUnsafe)
    Transaction(
      txTemplate.unsigned,
      AVector.empty,
      generatedOutputs = remainingBalances,
      txTemplate.inputSignatures,
      txTemplate.contractSignatures
    )
  }

  def nextTimeStamp(parentTs: TimeStamp): TimeStamp = {
    val resultTs = TimeStamp.now()
    if (resultTs <= parentTs) {
      parentTs.plusMillisUnsafe(1)
    } else {
      resultTs
    }
  }

  def isSame(utxos0: AVector[AssetOutputInfo], utxos1: AVector[AssetOutputInfo]): Boolean = {
    (utxos0.length == utxos1.length) && {
      val set = utxos0.toSet
      utxos1.forall(set.contains)
    }
  }

  def truncateTxs(
      txs: AVector[TransactionTemplate],
      maximalTxs: Int,
      maximalGas: GasBox
  ): AVector[TransactionTemplate] = {
    @tailrec
    def iter(gasSum: Int, index: Int): Int = {
      if (index < txs.length) {
        val newSum = gasSum + txs(index).unsigned.startGas.value
        if (newSum > 0 && newSum <= maximalGas.value) iter(newSum, index + 1) else index
      } else {
        index
      }
    }

    val maximalGasIndex = iter(0, 0)
    txs.take(math.min(maximalTxs, maximalGasIndex))
  }
}

trait SyncUtils {
  def getIntraSyncInventories(): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getIntraSyncInventoriesUnsafe())

  def getSyncLocators(): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getSyncLocatorsUnsafe())

  def getSyncInventories(
      locators: AVector[AVector[BlockHash]]
  ): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getSyncInventoriesUnsafe(locators))

  protected def getIntraSyncInventoriesUnsafe(): AVector[AVector[BlockHash]]

  protected def getSyncLocatorsUnsafe(): AVector[AVector[BlockHash]]

  protected def getSyncInventoriesUnsafe(
      locators: AVector[AVector[BlockHash]]
  ): AVector[AVector[BlockHash]]
}
