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

import scala.reflect.ClassTag

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.Utils
import org.alephium.flow.handler.FlowHandler.BlockFlowTemplate
import org.alephium.flow.mempool.{MemPool, MemPoolChanges, Normal, Reorg}
import org.alephium.flow.setting.MemPoolSetting
import org.alephium.io.{IOError, IOResult, IOUtils}
import org.alephium.protocol.BlockHash
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{MutableWorldState, StatefulScript, StatefulVM, WorldState}
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.util.{AVector, U256}

trait FlowUtils
    extends MultiChain
    with BlockFlowState
    with SyncUtils
    with ConflictedBlocks
    with StrictLogging {
  implicit def mempoolSetting: MemPoolSetting

  val mempools = AVector.tabulate(brokerConfig.groupNumPerBroker) { idx =>
    val group = GroupIndex.unsafe(brokerConfig.groupFrom + idx)
    MemPool.empty(group)
  }

  def getPool(mainGroup: GroupIndex): MemPool = {
    mempools(mainGroup.value - brokerConfig.groupFrom)
  }

  def getPool(chainIndex: ChainIndex): MemPool = {
    mempools(chainIndex.from.value - brokerConfig.groupFrom)
  }

  def calMemPoolChangesUnsafe(mainGroup: GroupIndex,
                              oldDeps: BlockDeps,
                              newDeps: BlockDeps): MemPoolChanges = {
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

  def updateMemPoolUnsafe(mainGroup: GroupIndex, newDeps: BlockDeps): Unit = {
    val oldDeps = getBestDeps(mainGroup)
    calMemPoolChangesUnsafe(mainGroup, oldDeps, newDeps) match {
      case Normal(toRemove) =>
        val removed = toRemove.foldWithIndex(0) { (sum, txs, toGroup) =>
          val toGroupIndex = GroupIndex.unsafe(toGroup)
          val index        = ChainIndex(mainGroup, toGroupIndex)
          sum + getPool(index).remove(index, txs.map(_.toTemplate))
        }
        if (removed > 0) {
          logger.debug(s"Normal update for #$mainGroup mempool: #$removed removed")
        }
      case Reorg(toRemove, toAdd) =>
        val (removed, added) = getPool(mainGroup).reorg(toRemove, toAdd)
        logger.debug(s"Reorg for #$mainGroup mempool: #$removed removed, #$added added")
    }
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps

  def updateBestDeps(): IOResult[Unit]

  def updateBestDepsUnsafe(): Unit

  def calBestDepsUnsafe(group: GroupIndex): BlockDeps

  def collectPooledTxs(chainIndex: ChainIndex): AVector[TransactionTemplate] = {
    getPool(chainIndex).collectForBlock(chainIndex, mempoolSetting.txMaxNumberPerBlock)
  }

  def filterValidInputsUnsafe(chainIndex: ChainIndex,
                              deps: BlockDeps,
                              txs: AVector[TransactionTemplate]): AVector[TransactionTemplate] = {
    val cachedWorldState = Utils.unsafe(getCachedWorldState(deps, chainIndex.from))
    txs.filter(tx => Utils.unsafe(cachedWorldState.containsAllInputs(tx)))
  }

  def collectTransactions(chainIndex: ChainIndex,
                          deps: BlockDeps): IOResult[AVector[TransactionTemplate]] =
    IOUtils.tryExecute {
      val candidates0 = collectPooledTxs(chainIndex)
      val candidates1 = FlowUtils.filterDoubleSpending(candidates0)
      val candidates2 = filterValidInputsUnsafe(chainIndex, deps, candidates1)
      val candidates3 = filterConflicts(chainIndex.from, deps, candidates2, getBlockUnsafe)
      candidates3
    }

  // all the inputs and double spending should have been checked
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def executeTxTemplates(
      chainIndex: ChainIndex,
      deps: BlockDeps,
      txTemplates: AVector[TransactionTemplate]): IOResult[AVector[Transaction]] = {
    if (chainIndex.isIntraGroup) {
      val parentHash = deps.getOutDep(chainIndex.to)
      val order      = Block.getScriptExecutionOrder(parentHash, txTemplates)
      val fullTxs    = Array.ofDim[Transaction](txTemplates.length + 1) // reverse 1 slot for coinbase tx
      txTemplates.foreachWithIndex {
        case (tx, index) =>
          if (tx.unsigned.scriptOpt.isEmpty) {
            fullTxs(index) = FlowUtils.convertNonScriptTx(tx)
          }
      }

      for {
        cachedWorldState <- getCachedWorldState(deps, chainIndex.from)
        _ <- order.foreachE[IOError] { scriptTxIndex =>
          val tx = txTemplates(scriptTxIndex)
          FlowUtils
            .generateFullTx(cachedWorldState, tx, tx.unsigned.scriptOpt.get)
            .map(fullTx => fullTxs(scriptTxIndex) = fullTx)
        }
      } yield AVector.unsafe(fullTxs, 0, txTemplates.length)
    } else {
      Right(txTemplates.map(FlowUtils.convertNonScriptTx))
    }
  }

  def prepareBlockFlow(chainIndex: ChainIndex): IOResult[BlockFlowTemplate] = {
    assume(brokerConfig.contains(chainIndex.from))
    val singleChain = getBlockChain(chainIndex)
    val bestDeps    = getBestDeps(chainIndex.from)
    for {
      target       <- singleChain.getHashTarget(bestDeps.getOutDep(chainIndex.to))
      parentHeader <- getBlockHeader(bestDeps.parentHash(chainIndex))
      candidates   <- collectTransactions(chainIndex, bestDeps)
      fullTxs      <- executeTxTemplates(chainIndex, bestDeps, candidates)
    } yield BlockFlowTemplate(chainIndex, bestDeps.deps, target, parentHeader.timestamp, fullTxs)
  }

  def prepareBlockFlowUnsafe(chainIndex: ChainIndex): BlockFlowTemplate = {
    Utils.unsafe(prepareBlockFlow(chainIndex))
  }
}

object FlowUtils {
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
    Transaction(txTemplate.unsigned,
                AVector.empty,
                AVector.empty,
                txTemplate.inputSignatures,
                txTemplate.contractSignatures)
  }

  def convertSuccessfulTx(txTemplate: TransactionTemplate,
                          result: TxScriptExecution): Transaction = {
    Transaction(txTemplate.unsigned,
                result.contractInputs,
                result.generatedOutputs,
                txTemplate.inputSignatures,
                txTemplate.contractSignatures)
  }

  def deductGas(inputs: AVector[TxOutput], gasFee: U256): AVector[TxOutput] = {
    inputs.replace(0, inputs(0).payGasUnsafe(gasFee))
  }

  def convertFailedScriptTx(worldState: MutableWorldState,
                            txTemplate: TransactionTemplate): IOResult[Transaction] = {
    worldState.getPreOutputsForVM(txTemplate).map { inputs =>
      assume(inputs.forall(_.isAsset))
      val remainingBalances = deductGas(inputs, txTemplate.gasFeeUnsafe)
      Transaction(txTemplate.unsigned,
                  AVector.empty,
                  generatedOutputs = remainingBalances,
                  txTemplate.inputSignatures,
                  txTemplate.contractSignatures)
    }
  }

  def generateFullTx(worldState: WorldState.Cached,
                     tx: TransactionTemplate,
                     script: StatefulScript): IOResult[Transaction] = {
    StatefulVM.runTxScript(worldState, tx, script, tx.unsigned.startGas) match {
      case Left(_)       => convertFailedScriptTx(worldState, tx)
      case Right(result) => Right(FlowUtils.convertSuccessfulTx(tx, result))
    }
  }
}

trait SyncUtils {
  def getIntraSyncInventories(
      remoteBroker: BrokerGroupInfo): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getIntraSyncInventoriesUnsafe(remoteBroker))

  def getSyncLocators(): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getSyncLocatorsUnsafe())

  def getSyncInventories(
      locators: AVector[AVector[BlockHash]]): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getSyncInventoriesUnsafe(locators))

  protected def getIntraSyncInventoriesUnsafe(
      remoteBroker: BrokerGroupInfo): AVector[AVector[BlockHash]]

  protected def getSyncLocatorsUnsafe(): AVector[AVector[BlockHash]]

  protected def getSyncInventoriesUnsafe(
      locators: AVector[AVector[BlockHash]]): AVector[AVector[BlockHash]]
}
