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

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{ChainIndex, GroupIndex, TransactionTemplate}
import org.alephium.util.{AVector, TimeStamp}

class TxHandlerBuffer private (
    val pool: MemPool
) {
  def size: Int = pool.size

  def add(transaction: TransactionTemplate, timeStamp: TimeStamp): Unit = {
    pool.add(TxHandlerBuffer.bufferChainIndex, transaction, timeStamp)
    ()
  }

  def getRootTxs(): AVector[TransactionTemplate] = {
    pool.collectNonSequentialTxs(TxHandlerBuffer.bufferChainIndex, Int.MaxValue)
  }

  def removeInvalidTx(tx: TransactionTemplate): Unit = {
    pool.removeUnusedTxs(AVector(tx))
    ()
  }

  def removeValidTx(tx: TransactionTemplate): Option[Iterable[TransactionTemplate]] = {
    val children = pool.flow.get(tx.id).flatMap(_.getChildren())
    pool.removeUsedTxs(AVector(tx))
    children.map(_.view.filter(_.isSource()).map(_.tx))
  }

  def clean(timeStampThreshold: TimeStamp): Int = {
    val oldTxs = pool._takeOldTxs(timeStampThreshold)
    pool.removeUnusedTxs(oldTxs)
  }

  def clear(): Unit = {
    pool.clear()
  }
}

object TxHandlerBuffer {
  // The buffer will be used to handle cross-group transactions, so the number of groups is set to 1
  private val bufferGroupConfig = new GroupConfig {
    override def groups: Int = 1
  }
  private val bufferChainIndex = ChainIndex.unsafe(0)(bufferGroupConfig)
  private val bufferGroupIndex = GroupIndex.unsafe(0)(bufferGroupConfig)

  // scalastyle:off magic.number
  def default(): TxHandlerBuffer = ofCapacity(500)
  // scalastyle:on magic.number

  def ofCapacity(capacity: Int): TxHandlerBuffer = {
    val pool = MemPool.ofCapacity(bufferGroupIndex, capacity)(bufferGroupConfig)
    new TxHandlerBuffer(pool)
  }
}
