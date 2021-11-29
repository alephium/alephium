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
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{GroupIndex, NoIndexModelGeneratorsLike, TransactionTemplate}
import org.alephium.util.{AVector, LockFixture, TimeStamp}

class PendingPoolSpec
    extends AlephiumFlowSpec
    with TxIndexesSpec.Fixture
    with LockFixture
    with NoIndexModelGeneratorsLike {
  private val dummyIndex = GroupIndex.unsafe(0)
  def now                = TimeStamp.now()
  def emptyTxIndexes     = TxIndexes.emptyPendingPool(dummyIndex)

  def checkTx(pool: PendingPool, tx: TransactionTemplate): Unit = {
    pool.txs.contains(tx.id) is true
    pool.timestamps.contains(tx.id) is true
    checkTx(pool.indexes, tx)
  }

  def addAndCheckTxs(pool: PendingPool, txs: AVector[(TransactionTemplate, TimeStamp)]): Unit = {
    txs.foreach { case (tx, timestamp) =>
      pool.add(tx, timestamp)
      checkTx(pool, tx)
    }
  }

  it should "add/remove tx" in {
    val tx   = transactionGen().retryUntil(_.chainIndex.from equals dummyIndex).sample.get.toTemplate
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
    pool.indexes is emptyTxIndexes
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

  it should "clean pending pool" in {
    val groupIndex             = GroupIndex.unsafe(0)
    val (privKey0, pubKey0, _) = genesisKeys(0)
    val (privKey1, pubKey1)    = groupIndex.generateKey

    val blockFlow0 = isolatedBlockFlow()
    val block0     = transfer(blockFlow0, privKey0, pubKey1, ALPH.alph(4))
    val tx0        = block0.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow0, block0)
    val block1 = transfer(blockFlow0, privKey1, pubKey0, ALPH.alph(1))
    val tx1    = block1.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow0, block1)
    val block2 = transfer(blockFlow0, privKey1, pubKey0, ALPH.alph(1))
    val tx2    = block2.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow0, block2)

    val pool       = PendingPool.empty(groupIndex, 10)
    val txIndexes  = TxIndexes.emptySharedPool(groupIndex)
    val timestamp1 = TimeStamp.now()
    val timestamp2 = timestamp1.plusSecondsUnsafe(1)
    val txs        = AVector(tx1 -> timestamp1, tx2 -> timestamp2)
    addAndCheckTxs(pool, txs)
    pool.clean(blockFlow, txIndexes) isE AVector(tx1 -> timestamp1, tx2 -> timestamp2)
    pool.contains(tx1.id) is false
    pool.contains(tx2.id) is false

    txIndexes.add(tx0)
    addAndCheckTxs(pool, txs)
    pool.clean(blockFlow, txIndexes) isE AVector.empty[(TransactionTemplate, TimeStamp)]
    pool.contains(tx1.id) is true
    pool.contains(tx2.id) is true

    pool.remove(tx1)
    pool.contains(tx1.id) is false
    pool.clean(blockFlow, txIndexes) isE AVector(tx2 -> timestamp2)
    pool.contains(tx2.id) is false

    txIndexes.remove(tx0)
    txIndexes.outputIndex.isEmpty is true
    addAndCheckTxs(pool, txs)
    addAndCheck(blockFlow, block0)
    pool.clean(blockFlow, txIndexes) isE AVector.empty[(TransactionTemplate, TimeStamp)]
    pool.contains(tx1.id) is true
    pool.contains(tx2.id) is true
  }
}
