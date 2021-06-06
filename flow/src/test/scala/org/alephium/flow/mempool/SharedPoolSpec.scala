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
import org.alephium.util.{AVector, LockFixture, U256}

class SharedPoolSpec extends AlephiumFlowSpec with LockFixture with NoIndexModelGeneratorsLike {
  val dummyIndex = ChainIndex.unsafe(0, 0)

  it should "initialize an empty tx pool" in {
    val pool = SharedPool.empty(dummyIndex, 3)
    pool.isFull is false
    pool.size is 0
  }

  it should "contain/add/remove for new transactions" in {
    val pool = SharedPool.empty(dummyIndex, 3)
    forAll(blockGen) { block =>
      val txTemplates = block.transactions.map(_.toTemplate)
      val numberAdded = pool.add(txTemplates)
      pool.size is numberAdded
      if (block.transactions.length > pool.capacity) {
        pool.isFull is true
      }
      block.transactions.foreachWithIndex { (tx, i) =>
        if (i < pool.size) pool.contains(tx.id) is true else pool.contains(tx.id) is false
      }
      val numberRemoved = pool.remove(txTemplates)
      numberRemoved is numberAdded
      pool.size is 0
      pool.isFull is false
      block.transactions.foreach(tx => pool.contains(tx.id) is false)
    }
  }

  trait Fixture extends WithLock {
    val pool        = SharedPool.empty(dummyIndex, 3)
    val block       = blockGen.sample.get
    val txTemplates = block.transactions.map(_.toTemplate)
    val txNum       = block.transactions.length
    lazy val rwl    = pool._getLock

    val sizeAfterAdd = if (txNum >= 3) 3 else txNum
  }

  it should "use read lock for containing" in new Fixture {
    checkReadLock(rwl)(true, pool.contains(block.transactions.head.id), false)
  }

  it should "use write lock for adding" in new Fixture {
    checkWriteLock(rwl)(0, pool.add(txTemplates), sizeAfterAdd)
  }

  it should "use write lock for removing" in new Fixture {
    pool.add(txTemplates)
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

    pool.add(AVector(tx1, tx2, tx3))

    pool.getAll() is AVector(tx2, tx3, tx1)

    pool.collectForBlock(1) is AVector(tx2)
    pool.collectForBlock(2) is AVector(tx2, tx3)
    pool.collectForBlock(10) is AVector(tx2, tx3, tx1)
  }
}
