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

package org.alephium.flow.mempool

import scala.collection.mutable
import scala.jdk.CollectionConverters.IteratorHasAsScala

import io.prometheus.metrics.core.metrics.{Counter, Gauge}
import io.prometheus.metrics.model.registry.PrometheusRegistry

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.core.FlowUtils.AssetOutputInfo
import org.alephium.flow.model.MempoolTxMetadata
import org.alephium.flow.setting.{MemPoolAdmissionLimits, MemPoolSetting}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, LockupScript}
import org.alephium.util.{AVector, RWLock, SimpleMap, TimeStamp, U256, ValueSortedMap}

/*
 * MemPool is the class to store all the unconfirmed transactions
 *
 * Transactions should be ordered according to weights. The weight is calculated based on fees
 */
// scalastyle:off number.of.methods
class MemPool private (
    group: GroupIndex,
    val flow: MemPool.Flow,
    // We could merge the following index into flow with multi-index sorted map
    timestamps: ValueSortedMap[TransactionId, TimeStamp],
    val sharedTxIndexes: TxIndexes,
    val capacity: Int,
    val capacityPerChain: Int,
    admissionLimits: MemPoolAdmissionLimits
)(implicit val groupConfig: GroupConfig)
    extends RWLock {
  private val chainUsages = Array.fill(groupConfig.chainNum)(new MemPoolUsage())
  private val payerUsages = mutable.HashMap.empty[MemPoolPayerKey, MemPoolUsage]
  private val maxTransactionsPerFeePayer = Math
    .max(
      1L,
      capacityPerChain.toLong *
        admissionLimits.maxTransactionsPerFeePayerPercent.toLong / 100L
    )
    .toInt

  def size: Int = readOnly(timestamps.size)

  private def _isFull(): Boolean = timestamps.size >= capacity

  def isFull(): Boolean = readOnly(_isFull())

  def contains(transaction: TransactionTemplate): Boolean = {
    contains(transaction.id)
  }

  def contains(txId: TransactionId): Boolean = readOnly(_contains(txId))

  def get(txId: TransactionId): Option[TransactionTemplate] = readOnly(flow.get(txId).map(_.tx))

  def getTimestamp(txId: TransactionId): Option[TimeStamp] = readOnly(timestamps.get(txId))

  private def _contains(txId: TransactionId): Boolean = {
    timestamps.contains(txId)
  }

  def isReady(txId: TransactionId): Boolean = readOnly {
    _contains(txId) && flow.unsafe(txId).isSource()
  }

  // No inter-dependent transactions
  def collectNonSequentialTxs(index: ChainIndex, maxNum: Int): AVector[TransactionTemplate] =
    readOnly {
      flow.takeSourceNodes(index.flattenIndex, maxNum, _.tx)
    }

  def collectAllTxs(index: ChainIndex, maxNum: Int): AVector[TransactionTemplate] = readOnly {
    flow.takeAllTxs(index.flattenIndex, maxNum)
  }

  def getAll(): AVector[TransactionTemplate] = readOnly {
    AVector.from(flow.allTxs.values().map(_.tx))
  }

  def getAllWithTimestamp(): AVector[(TransactionTemplate, TimeStamp)] = readOnly {
    AVector.from(flow.allTxs.values().map(node => (node.tx, node.timestamp)))
  }

  def getOutTxsWithTimestamp(): AVector[(TimeStamp, TransactionTemplate)] = readOnly {
    AVector.from(
      timestamps
        .entries()
        .map(e => e.getValue -> flow.unsafe(e.getKey))
        .filter(p => ChainIndex.checkFromGroup(p._2.chainIndex, group))
        .map(p => p._1 -> p._2.tx)
    )
  }

  def getTxs(txIds: AVector[TransactionId]): AVector[TransactionTemplate] = {
    txIds.fold(AVector.empty[TransactionTemplate]) { (acc, txId) =>
      readOnly(flow.get(txId)) match {
        case Some(node) => acc :+ node.tx
        case None       => acc
      }
    }
  }

  def isSpent(outputRef: TxOutputRef): Boolean = outputRef match {
    case ref: AssetOutputRef => isSpent(ref)
    case _                   => false
  }

  def isSpent(outputRef: AssetOutputRef): Boolean = readOnly {
    assume(outputRef.fromGroup == group)
    _isSpent(outputRef)
  }

  @inline private def _isSpent(outputRef: AssetOutputRef): Boolean = {
    sharedTxIndexes.isSpent(outputRef)
  }

  def isDoubleSpending(index: ChainIndex, tx: TransactionTemplate): Boolean = readOnly {
    assume(index.from == group)
    tx.unsigned.inputs.exists(input => _isSpent(input.outputRef))
  }

  private[flow] def checkFeePayerLimits(
      index: ChainIndex,
      tx: TransactionTemplate,
      metadata: MempoolTxMetadata
  ): Option[MemPool.FeePayerLimitExceeded] = readOnly {
    _checkFeePayerLimits(index, tx, metadata).map { result =>
      measureAdmissionRejected(index, result.reason)
      result
    }
  }

  private[mempool] def getChainUsage(index: ChainIndex): MemPool.UsageSnapshot = readOnly {
    chainUsages(index.flattenIndex).snapshot
  }

  private[mempool] def getFeePayerUsage(
      index: ChainIndex,
      feePayer: LockupScript.Asset
  ): MemPool.UsageSnapshot = readOnly {
    payerUsages
      .get(MemPoolPayerKey(index.flattenIndex, feePayer))
      .map(_.snapshot)
      .getOrElse(MemPool.UsageSnapshot.empty)
  }

  private[mempool] def add(
      index: ChainIndex,
      tx: TransactionTemplate,
      timeStamp: TimeStamp
  ): MemPool.AddToMemPoolResult = add(index, tx, timeStamp, None)

  private[mempool] def add(
      index: ChainIndex,
      tx: TransactionTemplate,
      timeStamp: TimeStamp,
      metadata: MempoolTxMetadata
  ): MemPool.AddToMemPoolResult = add(index, tx, timeStamp, Some(metadata))

  private def add(
      index: ChainIndex,
      tx: TransactionTemplate,
      timeStamp: TimeStamp,
      metadataOpt: Option[MempoolTxMetadata]
  ): MemPool.AddToMemPoolResult = writeOnly {
    if (_contains(tx.id)) {
      MemPool.AlreadyExisted
    } else {
      metadataOpt match {
        case None => addWithoutAdmissionControl(index, tx, timeStamp)
        case Some(_) if !admissionLimits.enabled =>
          addWithoutAdmissionControl(index, tx, timeStamp)
        case Some(_) if sharedTxIndexes.isDoubleSpending(tx) => MemPool.DoubleSpending
        case Some(metadata) =>
          _checkFeePayerLimits(index, tx, metadata) match {
            case Some(result) =>
              measureAdmissionRejected(index, result.reason)
              result
            case None =>
              _planEvictions(index, tx, metadata) match {
                case Some(toRemove) =>
                  toRemove.foreach(txId => if (_contains(txId)) _removeUnusedTx(txId))
                  _add(index, tx, timeStamp, Some(metadata))
                case None => MemPool.MemPoolIsFull
              }
          }
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  private def addWithoutAdmissionControl(
      index: ChainIndex,
      tx: TransactionTemplate,
      timestamp: TimeStamp
  ): MemPool.AddToMemPoolResult = {
    if (_isFull()) {
      val lowestWeightTxId = flow.allTxs.max // tx order is reversed
      val lowestWeightTx   = flow.unsafe(lowestWeightTxId).tx
      if (MemPool.txOrdering.lt(tx, lowestWeightTx)) {
        _removeUnusedTx(lowestWeightTxId)
        _add(index, tx, timestamp, None)
      } else {
        MemPool.MemPoolIsFull
      }
    } else {
      _add(index, tx, timestamp, None)
    }
  }

  private def _checkFeePayerLimits(
      index: ChainIndex,
      tx: TransactionTemplate,
      metadata: MempoolTxMetadata
  ): Option[MemPool.FeePayerLimitExceeded] = {
    if (!admissionLimits.enabled) {
      None
    } else {
      metadata.feePayer.flatMap { feePayer =>
        val usage = payerUsages.getOrElse(
          MemPoolPayerKey(index.flattenIndex, feePayer),
          MemPoolUsage.empty
        )
        val normalGasLimit = Math.max(
          1L,
          metadata.maximalGasPerBlock.value.toLong *
            admissionLimits.maxGasPerFeePayerPercentOfBlock.toLong / 100L
        )
        val txGas = tx.unsigned.gasAmount.value.toLong
        if (usage.transactionCount + 1 > maxTransactionsPerFeePayer) {
          Some(MemPool.FeePayerLimitExceeded(MemPool.FeePayerLimitReason.TransactionCount))
        } else if (txGas > normalGasLimit) {
          Option.when(usage.transactionCount != 0)(
            MemPool.FeePayerLimitExceeded(MemPool.FeePayerLimitReason.Gas)
          )
        } else if (usage.oversizedTransactionCount != 0 || usage.gas + txGas > normalGasLimit) {
          Some(MemPool.FeePayerLimitExceeded(MemPool.FeePayerLimitReason.Gas))
        } else {
          None
        }
      }
    }
  }

  // scalastyle:off method.length
  private def _planEvictions(
      index: ChainIndex,
      tx: TransactionTemplate,
      metadata: MempoolTxMetadata
  ): Option[AVector[TransactionId]] = {
    val chainUsage      = chainUsages(index.flattenIndex)
    val chainCountLimit = capacityPerChain
    val chainGasLimit =
      metadata.maximalGasPerBlock.value.toLong * admissionLimits.maxGasPerChainInBlocks.toLong
    val txGas = tx.unsigned.gasAmount.value.toLong

    var removedSize       = 0
    var removedChainCount = 0
    var removedChainGas   = 0L
    def fits(): Boolean = {
      timestamps.size - removedSize + 1 <= capacity &&
      chainUsage.transactionCount - removedChainCount + 1 <= chainCountLimit &&
      chainUsage.gas - removedChainGas + txGas <= chainGasLimit
    }

    if (fits()) {
      Some(AVector.empty)
    } else {
      val protectedTxs = collectAncestors(tx)
      val plannedTxs   = mutable.HashSet.empty[TransactionId]
      val roots        = mutable.ArrayBuffer.empty[TransactionId]
      val iterator     = flow.allTxs.orderedMap.descendingMap().values().iterator().asScala
      while (iterator.hasNext && !fits()) {
        val candidate = iterator.next()
        if (
          !plannedTxs.contains(candidate.tx.id) &&
          !protectedTxs.contains(candidate.tx.id) &&
          MemPool.txOrdering.lt(tx, candidate.tx)
        ) {
          val closure  = collectDescendants(candidate)
          val newNodes = closure.filterNot(node => plannedTxs.contains(node.tx.id))
          val needsChainCapacity =
            chainUsage.transactionCount - removedChainCount + 1 > chainCountLimit ||
              chainUsage.gas - removedChainGas + txGas > chainGasLimit
          val freesChainCapacity = newNodes.exists(node =>
            node.chainIndex == index.flattenIndex && isSourceGroupNode(node)
          )
          if (
            !closure.exists(node => protectedTxs.contains(node.tx.id)) &&
            (!needsChainCapacity || freesChainCapacity)
          ) {
            roots += candidate.tx.id
            newNodes.foreach { node =>
              plannedTxs += node.tx.id
              removedSize += 1
              if (node.chainIndex == index.flattenIndex && isSourceGroupNode(node)) {
                removedChainCount += 1
                removedChainGas += node.tx.unsigned.gasAmount.value.toLong
              }
            }
          }
        }
      }
      Option.when(fits())(AVector.from(roots))
    }
  }
  // scalastyle:on method.length

  private def collectAncestors(tx: TransactionTemplate): mutable.HashSet[TransactionId] = {
    val result = mutable.HashSet.empty[TransactionId]
    val stack  = mutable.ArrayBuffer.empty[MemPool.FlowNode]
    tx.unsigned.inputs.foreach { input =>
      sharedTxIndexes.outputIndex
        .get(input.outputRef)
        .flatMap { case (_, parentTx) => flow.get(parentTx.id) }
        .foreach(stack += _)
    }
    while (stack.nonEmpty) {
      val node = stack.remove(stack.length - 1)
      if (result.add(node.tx.id)) {
        node.getParents().foreach(_.foreach(stack += _))
      }
    }
    result
  }

  private def collectDescendants(node: MemPool.FlowNode): AVector[MemPool.FlowNode] = {
    val result = mutable.ArrayBuffer.empty[MemPool.FlowNode]
    val seen   = mutable.HashSet.empty[TransactionId]
    val stack  = mutable.ArrayBuffer(node)
    while (stack.nonEmpty) {
      val current = stack.remove(stack.length - 1)
      if (seen.add(current.tx.id)) {
        result += current
        current.getChildren().foreach(_.foreach(stack += _))
      }
    }
    AVector.from(result)
  }

  def addXGroupTx(
      index: ChainIndex,
      tx: TransactionTemplate,
      timestamp: TimeStamp
  ): Unit =
    writeOnly {
      if (!_contains(tx.id)) {
        assume(index.from != group)
        val children = sharedTxIndexes.addXGroupTx(tx, tx => flow.unsafe(tx.id))
        flow.addNewNode(
          MemPool.FlowNode(tx.id, tx, timestamp, index.flattenIndex, None, children, None, false)
        )
        timestamps.put(tx.id, timestamp)
      }
    }

  private def _add(
      index: ChainIndex,
      tx: TransactionTemplate,
      timestamp: TimeStamp,
      metadataOpt: Option[MempoolTxMetadata]
  ): MemPool.AddToMemPoolResult = {
    if (sharedTxIndexes.isDoubleSpending(tx)) {
      MemPool.DoubleSpending
    } else {
      val (parents, children) = sharedTxIndexes.add(tx, tx => flow.unsafe(tx.id))
      val feePayer            = metadataOpt.flatMap(_.feePayer)
      val isOversized         = metadataOpt.exists(metadata => isOversizedTx(tx, metadata))
      val node = MemPool.FlowNode(
        tx.id,
        tx,
        timestamp,
        index.flattenIndex,
        parents,
        children,
        feePayer,
        isOversized
      )
      flow.addNewNode(node)
      timestamps.put(tx.id, timestamp)
      increaseUsage(node)
      measureTransactionsTotalInc(index.flattenIndex)
      MemPool.AddedToMemPool(timestamp)
    }
  }

  private def isSourceGroupNode(node: MemPool.FlowNode): Boolean = {
    ChainIndex.checkFromGroup(node.chainIndex, group)
  }

  private def isOversizedTx(tx: TransactionTemplate, metadata: MempoolTxMetadata): Boolean = {
    admissionLimits.enabled && metadata.feePayer.nonEmpty &&
    tx.unsigned.gasAmount.value.toLong * 100L >
      metadata.maximalGasPerBlock.value.toLong *
      admissionLimits.maxGasPerFeePayerPercentOfBlock.toLong
  }

  private def increaseUsage(node: MemPool.FlowNode): Unit = {
    if (isSourceGroupNode(node)) {
      val gas        = node.tx.unsigned.gasAmount.value.toLong
      val chainUsage = chainUsages(node.chainIndex)
      chainUsage.add(gas, node.isOversized)
      node.feePayer.foreach { payer =>
        val key   = MemPoolPayerKey(node.chainIndex, payer)
        val usage = payerUsages.getOrElseUpdate(key, new MemPoolUsage())
        usage.add(gas, node.isOversized)
      }
      measureGasTotalInc(node.chainIndex, gas)
    }
  }

  private def decreaseUsage(node: MemPool.FlowNode): Unit = {
    if (isSourceGroupNode(node)) {
      val gas        = node.tx.unsigned.gasAmount.value.toLong
      val chainUsage = chainUsages(node.chainIndex)
      chainUsage.remove(gas, node.isOversized)
      node.feePayer.foreach { payer =>
        val key = MemPoolPayerKey(node.chainIndex, payer)
        payerUsages.get(key).foreach { usage =>
          usage.remove(gas, node.isOversized)
          if (usage.transactionCount == 0) {
            payerUsages.remove(key)
          }
        }
      }
      measureGasTotalDec(node.chainIndex, gas)
    }
  }

  private[mempool] def add(
      index: ChainIndex,
      transactions: AVector[TransactionTemplate],
      timeStamp: TimeStamp
  ): Int = {
    transactions.sumBy(add(index, _, timeStamp).addedCount)
  }

  private[mempool] def add(
      index: ChainIndex,
      transactions: AVector[TransactionTemplate],
      timeStamp: TimeStamp,
      metadata: MempoolTxMetadata
  ): Int = {
    transactions.sumBy(add(index, _, timeStamp, metadata).addedCount)
  }

  def removeUsedTxs(transactions: AVector[TransactionTemplate]): Int =
    remove(transactions, _removeUsedTx)

  def removeUnusedTx(transaction: TransactionTemplate): Unit = writeOnly {
    if (_contains(transaction.id)) {
      _removeUnusedTx(transaction.id)
    }
  }

  def removeUnusedTxs(transactions: AVector[TransactionTemplate]): Int =
    remove(transactions, _removeUnusedTx)

  @inline private def remove(
      transactions: AVector[TransactionTemplate],
      _remove: TransactionId => Unit
  ): Int = writeOnly {
    val sizeBefore = size
    transactions.foreach(tx => if (_contains(tx.id)) _remove(tx.id))
    val sizeAfter = size
    sizeBefore - sizeAfter
  }

  @inline private def _removeUsedTx(txId: TransactionId): Unit = {
    flow.removeNodeAndAncestors(txId, removeSideEffect)
  }

  @inline private def _removeUnusedTx(txId: TransactionId): Unit = {
    flow.removeNodeAndDescendants(txId, removeSideEffect)
  }

  @inline private def removeSideEffect(node: MemPool.FlowNode): Unit = {
    decreaseUsage(node)
    measureTransactionsTotalDec(node.chainIndex)
    timestamps.remove(node.tx.id)
    sharedTxIndexes.remove(node.tx)
  }

  def reorg(
      toRemove: AVector[(ChainIndex, AVector[Transaction])],
      toAdd: AVector[(ChainIndex, AVector[Transaction])],
      maximalGasPerBlock: GasBox
  ): (Int, Int) = {
    assume(toRemove.length == groupConfig.depsNum && toAdd.length == groupConfig.depsNum)
    val now = TimeStamp.now()

    // First, add transactions from short chains, then remove transactions from canonical chains
    val metadata = MempoolTxMetadata(None, maximalGasPerBlock)
    val added =
      toAdd.fold(0) { case (sum, (index, txs)) =>
        sum + add(index, txs.map(_.toTemplate), now, metadata)
      }
    val removed =
      toRemove.fold(0) { case (sum, (_, txs)) =>
        sum + removeUsedTxs(txs.map(_.toTemplate))
      }

    (removed, added)
  }

  def getRelevantUtxos(
      lockupScript: LockupScript,
      utxosInBlock: AVector[AssetOutputInfo]
  ): AVector[AssetOutputInfo] = readOnly {
    // TODO: optimize this once mempool is updated differently
    val newUtxos = sharedTxIndexes
      .getRelevantUtxos(lockupScript)
      .filter(utxo => !utxosInBlock.exists(_.ref == utxo.ref))

    (utxosInBlock ++ newUtxos).filterNot(asset => _isSpent(asset.ref))
  }

  def getOutput(outputRef: TxOutputRef): Option[AssetOutput] = outputRef match {
    case ref: AssetOutputRef => getOutput(ref)
    case _                   => None
  }

  // the output might have been spent
  def getOutput(outputRef: AssetOutputRef): Option[AssetOutput] = readOnly {
    sharedTxIndexes.outputIndex.get(outputRef).map(_._1)
  }

  def clear(): Unit = writeOnly {
    flow.clear()
    timestamps.clear()
    sharedTxIndexes.clear()
    chainUsages.foreach(_.clear())
    payerUsages.clear()
    transactionsTotalLabeled.foreach(_.set(0.0))
    gasTotalLabeled.foreach(_.set(0.0))
  }

  private[mempool] def _takeOldTxs(
      timeStampThreshold: TimeStamp
  ): AVector[TransactionTemplate] = {
    var buffer = AVector.empty[TransactionTemplate]
    flow.sourceTxs.foreach(
      _.values().foreach(node =>
        if (node.timestamp <= timeStampThreshold) {
          buffer = buffer :+ node.tx
        }
      )
    )
    buffer
  }

  // TODO: Optimize this
  def cleanInvalidTxs(
      blockFlow: BlockFlow,
      timeStampThreshold: TimeStamp
  ): Int = writeOnly {
    val oldTxs  = _takeOldTxs(timeStampThreshold)
    var removed = 0
    blockFlow.recheckInputs(group, oldTxs).foreach { invalidTxs =>
      removed += removeUnusedTxs(invalidTxs)
    }
    removed
  }

  def cleanUnconfirmedTxs(timeStampThreshold: TimeStamp): Int = writeOnly {
    removeUnusedTxs(_takeOldTxs(timeStampThreshold))
  }

  private val transactionsTotalLabeled = {
    groupConfig.cliqueChainIndexes.map(chainIndex =>
      MemPool.sharedPoolTransactionsTotal
        .labelValues(chainIndex.from.value.toString, chainIndex.to.value.toString)
    )
  }

  private val gasTotalLabeled = {
    groupConfig.cliqueChainIndexes.map(chainIndex =>
      MemPool.sharedPoolGasTotal
        .labelValues(chainIndex.from.value.toString, chainIndex.to.value.toString)
    )
  }

  def measureTransactionsTotalInc(index: Int): Unit = {
    if (ChainIndex.checkFromGroup(index, group)) {
      transactionsTotalLabeled(index).inc()
    }
  }

  def measureTransactionsTotalDec(index: Int): Unit = {
    if (ChainIndex.checkFromGroup(index, group)) {
      transactionsTotalLabeled(index).dec()
    }
  }

  private def measureGasTotalInc(index: Int, gas: Long): Unit = {
    gasTotalLabeled(index).inc(gas.toDouble)
  }

  private def measureGasTotalDec(index: Int, gas: Long): Unit = {
    gasTotalLabeled(index).dec(gas.toDouble)
  }

  private def measureAdmissionRejected(
      index: ChainIndex,
      reason: MemPool.FeePayerLimitReason
  ): Unit = {
    MemPool.feePayerAdmissionRejected
      .labelValues(index.from.value.toString, index.to.value.toString, reason.metricLabel)
      .inc()
  }
}

object MemPool {
  private val DisabledAdmissionLimits = MemPoolAdmissionLimits(
    enabled = false,
    maxGasPerChainInBlocks = 1,
    maxTransactionsPerFeePayerPercent = 1,
    maxGasPerFeePayerPercentOfBlock = 1
  )

  def empty(
      mainGroup: GroupIndex
  )(implicit groupConfig: GroupConfig, memPoolSetting: MemPoolSetting): MemPool = {
    val sharedTxIndex = TxIndexes.emptyMemPool(mainGroup)
    new MemPool(
      mainGroup,
      Flow.empty,
      ValueSortedMap.empty,
      sharedTxIndex,
      memPoolSetting.mempoolCapacityPerChain * groupConfig.groups,
      memPoolSetting.mempoolCapacityPerChain,
      memPoolSetting.admissionLimits
    )
  }

  def ofCapacity(
      mainGroup: GroupIndex,
      capacity: Int
  )(implicit groupConfig: GroupConfig): MemPool = {
    ofCapacity(mainGroup, capacity, DisabledAdmissionLimits)
  }

  private[mempool] def ofCapacity(
      mainGroup: GroupIndex,
      capacity: Int,
      admissionLimits: MemPoolAdmissionLimits
  )(implicit groupConfig: GroupConfig): MemPool = {
    ofCapacity(mainGroup, capacity, capacity, admissionLimits)
  }

  private[mempool] def ofCapacity(
      mainGroup: GroupIndex,
      capacity: Int,
      capacityPerChain: Int,
      admissionLimits: MemPoolAdmissionLimits
  )(implicit groupConfig: GroupConfig): MemPool = {
    val sharedTxIndex = TxIndexes.emptyMemPool(mainGroup)
    new MemPool(
      mainGroup,
      Flow.empty,
      ValueSortedMap.empty,
      sharedTxIndex,
      capacity,
      capacityPerChain,
      admissionLimits
    )
  }

  sealed trait AddToMemPoolResult {
    def addedCount: Int
  }
  final case class AddedToMemPool(seenAt: TimeStamp) extends AddToMemPoolResult {
    def addedCount: Int = 1
  }
  sealed trait AddTxFailed extends AddToMemPoolResult {
    def addedCount: Int = 0
  }
  case object MemPoolIsFull                                           extends AddTxFailed
  case object DoubleSpending                                          extends AddTxFailed
  case object AlreadyExisted                                          extends AddTxFailed
  case object AddedToOrphanPool                                       extends AddTxFailed
  final case class FeePayerLimitExceeded(reason: FeePayerLimitReason) extends AddTxFailed

  sealed trait FeePayerLimitReason {
    def metricLabel: String
  }
  object FeePayerLimitReason {
    case object TransactionCount extends FeePayerLimitReason {
      val metricLabel: String = "transaction_count"
    }
    case object Gas extends FeePayerLimitReason {
      val metricLabel: String = "gas"
    }
  }

  final private[mempool] case class UsageSnapshot(
      transactionCount: Int,
      gas: Long,
      oversizedTransactionCount: Int
  )
  private[mempool] object UsageSnapshot {
    val empty: UsageSnapshot = UsageSnapshot(0, 0L, 0)
  }

  val txOrdering: Ordering[TransactionTemplate] =
    Ordering
      .by[TransactionTemplate, (U256, Hash)](tx => (tx.unsigned.gasPrice.value, tx.id.value))
      .reverse // reverse the order so that higher gas tx can be at the front of an ordered collection

  implicit val nodeOrdering: Ordering[FlowNode] = {
    // sort the tx by timestamp in order to make sure that the parent tx
    // is in the front of the child tx if the gas prices are the same
    Ordering
      .by[FlowNode, U256](_.tx.unsigned.gasPrice.value)
      .reverse
      .orElse(Ordering.by[FlowNode, TimeStamp](_.timestamp))
      .orElse(Ordering.by[FlowNode, Hash](_.tx.id.value).reverse)
  }

  final case class FlowNode(
      key: TransactionId,
      tx: TransactionTemplate,
      timestamp: TimeStamp,
      chainIndex: Int,
      var _parents: Option[mutable.ArrayBuffer[FlowNode]],
      var _children: Option[mutable.ArrayBuffer[FlowNode]],
      feePayer: Option[LockupScript.Asset],
      isOversized: Boolean
  ) extends KeyedFlow.Node[TransactionId, FlowNode] {
    def getGroup(): Int = chainIndex
  }

  final case class Flow(
      sourceTxs: AVector[ValueSortedMap[TransactionId, FlowNode]],
      allTxs: ValueSortedMap[TransactionId, FlowNode]
  ) extends KeyedFlow[TransactionId, FlowNode](
        sourceTxs.as[SimpleMap[TransactionId, FlowNode]],
        allTxs
      ) {
    def takeAllTxs(sourceIndex: Int, maxNum: Int): AVector[TransactionTemplate] = {
      AVector.from(allTxs.values().filter(_.chainIndex == sourceIndex).map(_.tx).take(maxNum))
    }
  }

  object Flow {
    def empty(implicit groupConfig: GroupConfig): Flow =
      Flow(AVector.fill(groupConfig.chainNum)(ValueSortedMap.empty), ValueSortedMap.empty)
  }

  val sharedPoolTransactionsTotal: Gauge = Gauge
    .builder()
    .name("alephium_mempool_shared_pool_transactions_total")
    .help("Number of transactions in shared pool")
    .labelNames("group_index", "chain_index")
    .register(PrometheusRegistry.defaultRegistry)

  val sharedPoolGasTotal: Gauge = Gauge
    .builder()
    .name("alephium_mempool_shared_pool_gas_total")
    .help("Declared gas of transactions in shared pool")
    .labelNames("group_index", "chain_index")
    .register(PrometheusRegistry.defaultRegistry)

  val feePayerAdmissionRejected: Counter = Counter
    .builder()
    .name("alephium_mempool_fee_payer_admission_rejected_total")
    .help("Number of mempool transactions rejected by fee payer limits")
    .labelNames("from_group", "to_group", "reason")
    .register(PrometheusRegistry.defaultRegistry)
}
