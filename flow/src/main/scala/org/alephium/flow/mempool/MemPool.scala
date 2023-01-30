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

import io.prometheus.client.Gauge

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.core.FlowUtils.AssetOutputInfo
import org.alephium.flow.setting.MemPoolSetting
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
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
    val capacity: Int
)(implicit
    groupConfig: GroupConfig
) extends RWLock {
  def size: Int = readOnly(timestamps.size)

  private def _isFull(): Boolean = size >= capacity

  def isFull(): Boolean = readOnly(_isFull())

  def contains(transaction: TransactionTemplate): Boolean = {
    contains(transaction.id)
  }

  def contains(txId: TransactionId): Boolean = readOnly(_contains(txId))

  private def _contains(txId: TransactionId): Boolean = {
    timestamps.contains(txId)
  }

  def isReady(txId: TransactionId): Boolean = readOnly {
    _contains(txId) && flow.unsafe(txId).isSource()
  }

  def collectForBlock(index: ChainIndex, maxNum: Int): AVector[TransactionTemplate] = readOnly {
    flow.takeSourceNodes(index.flattenIndex, maxNum, _.tx)
  }

  def getAll(): AVector[TransactionTemplate] = readOnly {
    AVector.from(flow.allTxs.values().map(_.tx))
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

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  private[mempool] def add(
      index: ChainIndex,
      tx: TransactionTemplate,
      timeStamp: TimeStamp
  ): MemPool.NewTxCategory =
    writeOnly {
      if (_contains(tx.id)) {
        MemPool.AlreadyExisted
      } else if (_isFull()) {
        val lowestWeightTxId = flow.allTxs.max // tx order is reversed
        val lowestWeightTx   = flow.unsafe(lowestWeightTxId).tx
        if (MemPool.txOrdering.lt(tx, lowestWeightTx)) {
          _removeUnusedTx(lowestWeightTxId)
          _add(index, tx, timeStamp)
        } else {
          MemPool.MemPoolIsFull
        }
      } else {
        _add(index, tx, timeStamp)
      }
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
        flow.addNewNode(MemPool.FlowNode(tx.id, tx, timestamp, index.flattenIndex, None, children))
        timestamps.put(tx.id, timestamp)
      }
    }

  private def _add(
      index: ChainIndex,
      tx: TransactionTemplate,
      timestamp: TimeStamp
  ): MemPool.NewTxCategory = {
    if (sharedTxIndexes.isDoubleSpending(tx)) {
      MemPool.DoubleSpending
    } else {
      val (parents, children) = sharedTxIndexes.add(tx, tx => flow.unsafe(tx.id))
      flow.addNewNode(MemPool.FlowNode(tx.id, tx, timestamp, index.flattenIndex, parents, children))
      timestamps.put(tx.id, timestamp)
      measureTransactionsTotalInc(index.flattenIndex)
      MemPool.AddedToMemPool
    }
  }

  private[mempool] def add(
      index: ChainIndex,
      transactions: AVector[TransactionTemplate],
      timeStamp: TimeStamp
  ): Int = {
    transactions.sumBy(add(index, _, timeStamp).addedCount)
  }

  def removeUsedTxs(transactions: AVector[TransactionTemplate]): Int =
    remove(transactions, _removeUsedTx)

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
    measureTransactionsTotalDec(node.chainIndex)
    timestamps.remove(node.tx.id)
    sharedTxIndexes.remove(node.tx)
  }

  def reorg(
      toRemove: AVector[(ChainIndex, AVector[Transaction])],
      toAdd: AVector[(ChainIndex, AVector[Transaction])]
  ): (Int, Int) = {
    assume(toRemove.length == groupConfig.depsNum && toAdd.length == groupConfig.depsNum)
    val now = TimeStamp.now()

    // First, add transactions from short chains, then remove transactions from canonical chains
    val added =
      toAdd.fold(0) { case (sum, (index, txs)) =>
        sum + add(index, txs.map(_.toTemplate), now)
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
    val newUtxos = sharedTxIndexes.getRelevantUtxos(lockupScript)

    (utxosInBlock ++ newUtxos).filterNot(asset => _isSpent(asset.ref))
  }

  def getOutput(outputRef: TxOutputRef): Option[TxOutput] = outputRef match {
    case ref: AssetOutputRef => getOutput(ref)
    case _                   => None
  }

  // the output might have been spent
  def getOutput(outputRef: AssetOutputRef): Option[TxOutput] = readOnly {
    sharedTxIndexes.outputIndex.get(outputRef).map(_._1)
  }

  def clear(): Unit = writeOnly {
    flow.clear()
    timestamps.clear()
    sharedTxIndexes.clear()
    transactionsTotalLabeled.foreach(_.set(0.0))
  }

  private def _takeOldTxs(
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

  def clean(
      blockFlow: BlockFlow,
      timeStampThreshold: TimeStamp
  ): Unit = writeOnly {
    val oldTxs = _takeOldTxs(timeStampThreshold)
    blockFlow.recheckInputs(group, oldTxs).foreach { invalidTxs =>
      removeUnusedTxs(invalidTxs)
    }
  }

  private val transactionsTotalLabeled = {
    groupConfig.cliqueChainIndexes.map(chainIndex =>
      MemPool.sharedPoolTransactionsTotal
        .labels(chainIndex.from.value.toString, chainIndex.to.value.toString)
    )
  }

  def measureTransactionsTotalInc(index: Int): Unit = {
    transactionsTotalLabeled(index).inc()
  }

  def measureTransactionsTotalDec(index: Int): Unit = {
    if (ChainIndex.checkFromGroup(index, group)) {
      transactionsTotalLabeled(index).dec()
    }
  }
}

object MemPool {
  def empty(
      mainGroup: GroupIndex
  )(implicit groupConfig: GroupConfig, memPoolSetting: MemPoolSetting): MemPool = {
    val sharedTxIndex = TxIndexes.emptyMemPool(mainGroup)
    new MemPool(
      mainGroup,
      Flow.empty,
      ValueSortedMap.empty,
      sharedTxIndex,
      memPoolSetting.mempoolCapacityPerChain * groupConfig.groups
    )
  }

  def ofCapacity(
      mainGroup: GroupIndex,
      capacity: Int
  )(implicit groupConfig: GroupConfig): MemPool = {
    val sharedTxIndex = TxIndexes.emptyMemPool(mainGroup)
    new MemPool(
      mainGroup,
      Flow.empty,
      ValueSortedMap.empty,
      sharedTxIndex,
      capacity
    )
  }

  sealed trait NewTxCategory {
    def addedCount: Int
  }
  case object AddedToMemPool extends NewTxCategory {
    def addedCount: Int = 1
  }
  sealed trait AddTxFailed extends NewTxCategory {
    def addedCount: Int = 0
  }
  case object MemPoolIsFull  extends AddTxFailed
  case object DoubleSpending extends AddTxFailed
  case object AlreadyExisted extends AddTxFailed

  val txOrdering: Ordering[TransactionTemplate] =
    Ordering
      .by[TransactionTemplate, (U256, Hash)](tx => (tx.unsigned.gasPrice.value, tx.id.value))
      .reverse // reverse the order so that higher gas tx can be at the front of an ordered collection

  implicit val nodeOrdering: Ordering[FlowNode] = {
    Ordering.by[FlowNode, TransactionTemplate](_.tx)(txOrdering)
  }

  final case class FlowNode(
      key: TransactionId,
      tx: TransactionTemplate,
      timestamp: TimeStamp,
      chainIndex: Int,
      var _parents: Option[mutable.ArrayBuffer[FlowNode]],
      var _children: Option[mutable.ArrayBuffer[FlowNode]]
  ) extends KeyedFlow.Node[TransactionId, FlowNode] {
    def getGroup(): Int = chainIndex
  }

  final case class Flow(
      sourceTxs: AVector[ValueSortedMap[TransactionId, FlowNode]],
      allTxs: ValueSortedMap[TransactionId, FlowNode]
  ) extends KeyedFlow[TransactionId, FlowNode](
        sourceTxs.as[SimpleMap[TransactionId, FlowNode]],
        allTxs
      ) {}

  object Flow {
    def empty(implicit groupConfig: GroupConfig): Flow =
      Flow(AVector.fill(groupConfig.chainNum)(ValueSortedMap.empty), ValueSortedMap.empty)
  }

  val sharedPoolTransactionsTotal: Gauge = Gauge
    .build(
      "alephium_mempool_shared_pool_transactions_total",
      "Number of transactions in shared pool"
    )
    .labelNames("group_index", "chain_index")
    .register()
}
