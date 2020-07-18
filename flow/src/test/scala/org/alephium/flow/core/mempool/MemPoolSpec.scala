package org.alephium.flow.core.mempool

import scala.util.Random

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model.{GroupIndex, NoIndexModelGeneratorsLike, Transaction}
import org.alephium.util.{AVector, LockFixture}

class MemPoolSpec extends AlephiumFlowSpec with LockFixture with NoIndexModelGeneratorsLike {
  it should "initialize an empty pool" in {
    val pool = MemPool.empty(GroupIndex.unsafe(0))
    pool.size is 0
  }

  it should "contain/add/remove for transactions" in {
    forAll(blockGen) { block =>
      val group =
        GroupIndex.unsafe(config.brokerInfo.groupFrom + Random.nextInt(config.groupNumPerBroker))
      val pool  = MemPool.empty(group)
      val index = block.chainIndex
      if (index.from.equals(group)) {
        block.transactions.foreach(pool.contains(index, _) is false)
        val weightedTxs = block.transactions.map((_, 1.0))
        pool.add(index, weightedTxs) is block.transactions.length
        pool.size is block.transactions.length
        block.transactions.foreach(pool.contains(index, _) is true)
        pool.remove(index, block.transactions) is block.transactions.length
        pool.size is 0
      } else {
        assertThrows[AssertionError](block.transactions.foreach(pool.contains(index, _)))
      }
    }
  }

  trait Fixture extends WithLock {
    val group       = GroupIndex.unsafe(0)
    val pool        = MemPool.empty(group)
    val block       = blockGenOf(group).retryUntil(_.transactions.nonEmpty).sample.get
    val weightedTxs = block.transactions.map((_, 1.0))
    val txNum       = block.transactions.length
    val rwl         = pool._getLock

    val chainIndex   = block.chainIndex
    val sizeAfterAdd = if (txNum >= 3) 3 else txNum
  }

  it should "use read lock for contains" in new Fixture {
    checkReadLock(rwl)(true, pool.contains(chainIndex, block.transactions.head), false)
  }

  it should "use read lock for add" in new Fixture {
    checkLockUsed(rwl)(0, pool.add(chainIndex, weightedTxs), txNum)
    pool.clear()
    checkNoWriteLock(rwl)(0, pool.add(chainIndex, weightedTxs), txNum)
  }

  it should "use read lock for remove" in new Fixture {
    checkReadLock(rwl)(1, pool.remove(chainIndex, block.transactions), 0)
  }

  it should "use write lock for reorg" in new Fixture {
    val foo = AVector.fill(config.groups)(AVector.empty[Transaction])
    val bar = AVector.fill(config.groups)(AVector.empty[(Transaction, Double)])
    checkWriteLock(rwl)((1, 1), pool.reorg(foo, bar), (0, 0))
  }
}
