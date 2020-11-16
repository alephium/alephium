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
import org.alephium.protocol.model.{GroupIndex, NoIndexModelGeneratorsLike, Transaction}
import org.alephium.util.{AVector, LockFixture, Random}

class MemPoolSpec extends AlephiumFlowSpec with LockFixture with NoIndexModelGeneratorsLike {
  it should "initialize an empty pool" in {
    val pool = MemPool.empty(GroupIndex.unsafe(0))
    pool.size is 0
  }

  it should "contain/add/remove for transactions" in {
    forAll(blockGen) { block =>
      val txTemplates = block.transactions.map(_.toTemplate)
      val group =
        GroupIndex.unsafe(
          brokerConfig.groupFrom + Random.source.nextInt(brokerConfig.groupNumPerBroker))
      val pool  = MemPool.empty(group)
      val index = block.chainIndex
      if (index.from.equals(group)) {
        txTemplates.foreach(pool.contains(index, _) is false)
        val weightedTxs = txTemplates.map((_, 1.0))
        pool.add(index, weightedTxs) is block.transactions.length
        pool.size is block.transactions.length
        txTemplates.foreach(pool.contains(index, _) is true)
        pool.remove(index, txTemplates) is block.transactions.length
        pool.size is 0
      } else {
        assertThrows[AssertionError](txTemplates.foreach(pool.contains(index, _)))
      }
    }
  }

  trait Fixture extends WithLock {
    val group       = GroupIndex.unsafe(0)
    val pool        = MemPool.empty(group)
    val block       = blockGenOf(group).retryUntil(_.transactions.nonEmpty).sample.get
    val txTemplates = block.transactions.map(_.toTemplate)
    val weightedTxs = txTemplates.map((_, 1.0))
    val txNum       = block.transactions.length
    val rwl         = pool._getLock

    val chainIndex   = block.chainIndex
    val sizeAfterAdd = if (txNum >= 3) 3 else txNum
  }

  it should "use read lock for contains" in new Fixture {
    checkReadLock(rwl)(true, pool.contains(chainIndex, txTemplates.head), false)
  }

  it should "use read lock for add" in new Fixture {
    checkLockUsed(rwl)(0, pool.add(chainIndex, weightedTxs), txNum)
    pool.clear()
    checkNoWriteLock(rwl)(0, pool.add(chainIndex, weightedTxs), txNum)
  }

  it should "use read lock for remove" in new Fixture {
    checkReadLock(rwl)(1, pool.remove(chainIndex, txTemplates), 0)
  }

  it should "use write lock for reorg" in new Fixture {
    val foo = AVector.fill(brokerConfig.groups)(AVector.empty[Transaction])
    val bar = AVector.fill(brokerConfig.groups)(AVector.empty[(Transaction, Double)])
    checkWriteLock(rwl)((1, 1), pool.reorg(foo, bar), (0, 0))
  }
}
