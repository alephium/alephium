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

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model.{GroupIndex, NoIndexModelGeneratorsLike, TransactionTemplate}
import org.alephium.util.{LockFixture, TimeStamp}

class PendingPoolSpec
    extends AlephiumFlowSpec
    with TxIndexesSpec.Fixture
    with LockFixture
    with NoIndexModelGeneratorsLike {
  private val dummyIndex = GroupIndex.unsafe(0)
  def now                = TimeStamp.now()

  def checkTx(pool: PendingPool, tx: TransactionTemplate): Unit = {
    pool.txs.contains(tx.id) is true
    pool.timestamps.contains(tx.id) is true
    checkTx(pool.indexes, tx)
  }

  it should "add/remove tx" in {
    val tx   = transactionGen().sample.get.toTemplate
    val pool = PendingPool.empty(dummyIndex, 10)
    pool.add(tx, now)
    pool.add(tx, now) // for idempotent
    pool.txs.contains(tx.id) is true
    pool.timestamps.contains(tx.id) is true
    checkTx(pool, tx)

    pool.remove(tx)
    pool.remove(tx) // for idempotent
    pool.txs.isEmpty is true
    pool.timestamps.isEmpty is true
    pool.indexes is TxIndexes.emptyPendingPool
  }

  it should "work with capacity" in {
    val tx0  = transactionGen().sample.get.toTemplate
    val pool = PendingPool.empty(dummyIndex, 1)
    pool.isFull() is false
    pool.add(tx0, now) is true
    pool.isFull() is true
    pool.add(tx0, now) is true

    val tx1 = transactionGen().sample.get.toTemplate
    pool.add(tx1, now) is false
    pool.remove(tx0)
    pool.add(tx1, now) is true
  }
}
