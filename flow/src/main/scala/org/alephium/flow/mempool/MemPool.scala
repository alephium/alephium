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

import org.alephium.flow.setting.MemPoolSetting
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{ChainIndex, GroupIndex, Transaction, TransactionTemplate}
import org.alephium.util.{AVector, RWLock}

/*
 * MemPool is the class to store all the pending transactions
 *
 * Transactions should be ordered according to weights. The weight is calculated based on fees
 */
class MemPool private (group: GroupIndex, pools: AVector[TxPool])(implicit groupConfig: GroupConfig)
    extends RWLock {
  def getPool(index: ChainIndex): TxPool = {
    assume(group == index.from)
    pools(index.to.value)
  }

  def size: Int = pools.sumBy(_.size)

  def contains(index: ChainIndex, transaction: TransactionTemplate): Boolean = readOnly {
    contains(index, transaction.id)
  }

  def contains(index: ChainIndex, txId: Hash): Boolean = readOnly {
    getPool(index).contains(txId)
  }

  def collectForBlock(index: ChainIndex, maxNum: Int): AVector[TransactionTemplate] = readOnly {
    getPool(index).collectForBlock(maxNum)
  }

  def getAll(index: ChainIndex): AVector[TransactionTemplate] = readOnly {
    getPool(index).getAll
  }

  def add(index: ChainIndex, transactions: AVector[TransactionTemplate]): Int = readOnly {
    getPool(index).add(transactions)
  }

  def remove(index: ChainIndex, transactions: AVector[TransactionTemplate]): Int = readOnly {
    getPool(index).remove(transactions)
  }

  // Note: we lock the mem pool so that we could update all the transaction pools
  def reorg(toRemove: AVector[AVector[Transaction]],
            toAdd: AVector[AVector[Transaction]]): (Int, Int) = writeOnly {
    assume(toRemove.length == groupConfig.groups && toAdd.length == groupConfig.groups)

    // First, add transactions from short chains, then remove transactions from canonical chains
    val added =
      toAdd.foldWithIndex(0)((sum, txs, toGroup) => sum + pools(toGroup).add(txs.map(_.toTemplate)))
    val removed = toRemove.foldWithIndex(0)((sum, txs, toGroup) =>
      sum + pools(toGroup).remove(txs.map(_.toTemplate)))
    (removed, added)
  }

  def clear(): Unit = writeOnly {
    pools.foreach(_.clear())
  }
}

object MemPool {
  def empty(groupIndex: GroupIndex)(implicit groupConfig: GroupConfig,
                                    memPoolSetting: MemPoolSetting): MemPool = {
    val pools = AVector.fill(groupConfig.groups)(TxPool.empty(memPoolSetting.txPoolCapacity))
    new MemPool(groupIndex, pools)
  }
}
