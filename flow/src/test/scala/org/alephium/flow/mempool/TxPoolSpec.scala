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
import org.alephium.protocol.model.NoIndexModelGeneratorsLike
import org.alephium.util.LockFixture

class TxPoolSpec extends AlephiumFlowSpec with LockFixture with NoIndexModelGeneratorsLike {
  it should "initialize an empty tx pool" in {
    val pool = TxPool.empty(3)
    pool.isFull is false
    pool.size is 0
  }

  it should "contain/add/remove for new transactions" in {
    val pool = TxPool.empty(3)
    forAll(blockGen) { block =>
      val weightedTxs = block.transactions.map((_, 1.0))
      val numberAdded = pool.add(weightedTxs)
      pool.size is numberAdded
      if (block.transactions.length > pool.capacity) {
        pool.isFull is true
      }
      block.transactions.foreachWithIndex { (tx, i) =>
        if (i < pool.size) pool.contains(tx) is true else pool.contains(tx) is false
      }
      val numberRemoved = pool.remove(block.transactions)
      numberRemoved is numberAdded
      pool.size is 0
      pool.isFull is false
      block.transactions.foreach(tx => pool.contains(tx) is false)
    }
  }

  trait Fixture extends WithLock {
    val pool        = TxPool.empty(3)
    val block       = blockGen.sample.get
    val weightedTxs = block.transactions.map((_, 1.0))
    val txNum       = block.transactions.length
    val rwl         = pool._getLock

    val sizeAfterAdd = if (txNum >= 3) 3 else txNum
  }

  it should "use read lock for containing" in new Fixture {
    checkReadLock(rwl)(true, pool.contains(block.transactions.head), false)
  }

  it should "use write lock for adding" in new Fixture {
    checkWriteLock(rwl)(0, pool.add(weightedTxs), sizeAfterAdd)
  }

  it should "use write lock for removing" in new Fixture {
    pool.add(weightedTxs)
    checkWriteLock(rwl)(0, pool.remove(block.transactions), sizeAfterAdd)
  }
}
