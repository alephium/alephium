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
import org.alephium.protocol.model._
import org.alephium.protocol.vm.GasPrice
import org.alephium.util.{AVector, LockFixture, TimeStamp, U256}

class SharedPoolSpec
    extends AlephiumFlowSpec
    with LockFixture
    with NoIndexModelGeneratorsLike
    with TxIndexesSpec.Fixture {
  val dummyIndex     = ChainIndex.unsafe(0, 0)
  def now            = TimeStamp.now()
  def emptyTxIndexes = TxIndexes.emptySharedPool(dummyIndex.from)

  def checkTx(pool: SharedPool, tx: TransactionTemplate): Unit = {
    pool.txs.contains(tx.id) is true
    pool.timestamps.contains(tx.id) is true
    checkTx(pool.sharedTxIndex, tx)
  }

  it should "initialize an empty tx pool" in {
    val pool = SharedPool.empty(dummyIndex, 3, emptyTxIndexes)
    pool.isFull() is false
    pool.size is 0
  }

  it should "contain/add/remove for new transactions" in {
    val pool = SharedPool.empty(dummyIndex, 3, emptyTxIndexes)
    forAll(blockGen) { block =>
      val txTemplates = block.transactions.map(_.toTemplate)
      val numberAdded = pool.add(txTemplates, now)
      if (block.transactions.length > pool.capacity) {
        pool.isFull() is true
        (numberAdded >= pool.size) is true
        pool.txs.getAll().toSet is block.transactions
          .map(_.toTemplate)
          .sorted(SharedPool.txOrdering)
          .takeRight(3)
          .toSet
      } else {
        pool.size is numberAdded
      }
      val poolSize      = pool.size
      val numberRemoved = pool.remove(txTemplates)
      numberRemoved is poolSize
      pool.size is 0
      pool.isFull() is false
      pool.txs.isEmpty is true
      pool.timestamps.isEmpty is true
      pool.sharedTxIndex is emptyTxIndexes
    }
  }

  trait Fixture extends WithLock {
    val pool        = SharedPool.empty(dummyIndex, Int.MaxValue, emptyTxIndexes)
    val block       = blockGen.sample.get
    val txTemplates = block.transactions.map(_.toTemplate)
    val txNum       = block.transactions.length
    lazy val rwl    = pool._getLock

    val sizeAfterAdd = txNum
  }

  it should "use read lock for containing" in new Fixture {
    checkReadLock(rwl)(true, pool.contains(block.transactions.head.id), false)
  }

  it should "use read lock for getting" in new Fixture {
    pool.add(txTemplates, now)
    val txIds = block.transactions.map(_.id) :+ TransactionId.generate
    checkReadLock(rwl)(AVector.empty, pool.getTxs(txIds), txTemplates)
    checkReadLock(rwl)(AVector.empty, pool.getTxs(block.transactions.map(_.id)), txTemplates)
  }

  it should "use write lock for adding" in new Fixture {
    checkWriteLock(rwl)(0, pool.add(txTemplates, now), sizeAfterAdd)
  }

  it should "use write lock for removing" in new Fixture {
    pool.add(txTemplates, now)
    checkWriteLock(rwl)(0, pool.remove(txTemplates), sizeAfterAdd)
  }

  it should "order txs" in new Fixture {
    def txGen(gasPrice: U256): TransactionTemplate = {
      val tx: Transaction = transactionGen().sample.get
      tx.toTemplate.copy(unsigned = tx.unsigned.copy(gasPrice = GasPrice(gasPrice)))
    }

    val tx1 = txGen(U256.unsafe(1))
    val tx2 = txGen(U256.unsafe(3))
    val tx3 = txGen(U256.unsafe(2))

    pool.add(AVector(tx1, tx2, tx3), now)

    pool.getAll().toSet is Set(tx2, tx3, tx1)

    pool.collectForBlock(1) is AVector(tx2)
    pool.collectForBlock(2) is AVector(tx2, tx3)
    pool.collectForBlock(10) is AVector(tx2, tx3, tx1)
  }

  it should "consider capacity" in {
    val pool = SharedPool.empty(dummyIndex, 1, emptyTxIndexes)

    val tx0 = transactionGen().sample.get.toTemplate
    pool.add(tx0, now) is true
    checkTx(pool, tx0)

    val tx1 = {
      val tmp = transactionGen().sample.get.toTemplate
      tmp.copy(unsigned = tmp.unsigned.copy(gasPrice = GasPrice(tx0.unsigned.gasPrice.value / 2)))
    }
    pool.add(tx1, now) is false
    checkTx(pool, tx0)

    val tx2 = {
      val tmp = transactionGen().sample.get.toTemplate
      tmp.copy(unsigned = tmp.unsigned.copy(gasPrice = GasPrice(tx0.unsigned.gasPrice.value * 2)))
    }
    pool.add(tx2, now) is true
    checkTx(pool, tx2)
  }
}
