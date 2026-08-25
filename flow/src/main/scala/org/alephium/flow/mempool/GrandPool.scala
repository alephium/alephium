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

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.MempoolTxMetadata
import org.alephium.flow.setting.MemPoolSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{
  ChainIndex,
  GroupIndex,
  Transaction,
  TransactionId,
  TransactionTemplate
}
import org.alephium.protocol.vm.GasBox
import org.alephium.util.{AVector, OptionF, TimeStamp}

class GrandPool(val mempools: AVector[MemPool], val orphanPool: OrphanPool)(implicit
    val brokerConfig: BrokerConfig
) {
  def size: Int = mempools.fold(0)(_ + _.size)

  @inline def getMemPool(mainGroup: GroupIndex): MemPool = {
    mempools(brokerConfig.groupIndexOfBroker(mainGroup))
  }

  def get(txId: TransactionId): Option[TransactionTemplate] = {
    OptionF.getAny(mempools.toIterable)(_.get(txId))
  }

  def add(
      index: ChainIndex,
      transactions: AVector[TransactionTemplate],
      timeStamp: TimeStamp
  ): Int = {
    transactions.sumBy(add(index, _, timeStamp).addedCount)
  }

  def add(
      index: ChainIndex,
      tx: TransactionTemplate,
      timestamp: TimeStamp
  ): MemPool.AddToMemPoolResult = add(index, tx, timestamp, None)

  private[flow] def add(
      index: ChainIndex,
      tx: TransactionTemplate,
      timestamp: TimeStamp,
      metadata: MempoolTxMetadata
  ): MemPool.AddToMemPoolResult = add(index, tx, timestamp, Some(metadata))

  @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
  private def add(
      index: ChainIndex,
      tx: TransactionTemplate,
      timestamp: TimeStamp,
      metadataOpt: Option[MempoolTxMetadata]
  ): MemPool.AddToMemPoolResult = {
    val sourcePool = getMemPool(index.from)
    val outcome    = sourcePool.addAndCollectEvicted(index, tx, timestamp, metadataOpt)
    removeMirroredTransactions(index.from, outcome.evicted)
    val result = outcome.result
    if (index.isIntraGroup) {
      result
    } else {
      if (!result.isInstanceOf[MemPool.AddTxFailed] && brokerConfig.contains(index.to)) {
        getMemPool(index.to).addXGroupTx(index, tx, timestamp)
      }
      result
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.While"))
  private def removeMirroredTransactions(
      initialGroup: GroupIndex,
      transactions: AVector[TransactionTemplate]
  ): Unit = {
    val pending = mutable.Queue.empty[(GroupIndex, TransactionTemplate)]
    transactions.foreach(transaction => pending.enqueue(initialGroup -> transaction))
    val visited = mutable.HashSet.empty[(GroupIndex, TransactionId)]

    while (pending.nonEmpty) {
      val (removedFrom, transaction) = pending.dequeue()
      if (visited.add(removedFrom -> transaction.id)) {
        transaction.chainIndexOpt.foreach { chainIndex =>
          if (
            !chainIndex.isIntraGroup &&
            removedFrom == chainIndex.from &&
            brokerConfig.contains(chainIndex.to)
          ) {
            getMemPool(chainIndex.to).removeUnusedTxAndCollect(transaction).foreach { removed =>
              pending.enqueue(chainIndex.to -> removed)
            }
          }
        }
      }
    }
  }

  def reorg(
      mainGroup: GroupIndex,
      toRemove: AVector[(ChainIndex, AVector[Transaction])],
      toAdd: AVector[(ChainIndex, AVector[Transaction])],
      maximalGasPerBlock: GasBox
  ): (Int, Int) = {
    val outcome = getMemPool(mainGroup).reorgAndCollectEvicted(toRemove, toAdd, maximalGasPerBlock)
    val confirmedTxIds = mutable.HashSet.empty[TransactionId]
    toRemove.foreach { case (_, transactions) =>
      transactions.foreach(transaction => confirmedTxIds.add(transaction.id))
    }
    removeMirroredTransactions(
      mainGroup,
      outcome.evicted.filterNot(transaction => confirmedTxIds.contains(transaction.id))
    )
    (outcome.removed, outcome.added)
  }

  def getOutTxsWithTimestamp(): AVector[(TimeStamp, TransactionTemplate)] = {
    mempools.flatMap(_.getOutTxsWithTimestamp())
  }

  def cleanInvalidTxs(
      blockFlow: BlockFlow,
      timeStampThreshold: TimeStamp
  ): Int = {
    mempools.fold(0)(_ + _.cleanInvalidTxs(blockFlow, timeStampThreshold))
  }

  def cleanMemPool(blockFlow: BlockFlow, now: TimeStamp)(implicit
      memPoolSetting: MemPoolSetting
  ): Unit = {
    val unconfirmedTxThreshold = now.minusUnsafe(memPoolSetting.unconfirmedTxExpiryDuration)
    mempools.foreach(_.cleanUnconfirmedTxs(unconfirmedTxThreshold))
    cleanInvalidTxs(blockFlow, now.minusUnsafe(memPoolSetting.cleanMempoolFrequency))
    ()
  }

  def clear(): Unit = {
    mempools.foreach(_.clear())
    orphanPool.clear()
  }

  def validateAllTxs(blockFlow: BlockFlow): Int = {
    cleanInvalidTxs(blockFlow, TimeStamp.now())
  }
}

object GrandPool {
  def empty(implicit brokerConfig: BrokerConfig, memPoolSetting: MemPoolSetting): GrandPool = {
    val mempools = AVector.tabulate(brokerConfig.groupNumPerBroker) { idx =>
      val group = GroupIndex.unsafe(brokerConfig.groupRange(idx))
      MemPool.empty(group)
    }
    new GrandPool(mempools, OrphanPool.default())
  }
}
