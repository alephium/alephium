package org.alephium.flow.core.mempool

import scala.util.Random

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model.{GroupIndex, ModelGen, Transaction}
import org.alephium.util.AVector

class MemPoolSpec extends AlephiumFlowSpec with LockFixture {
  it should "initialize an empty pool" in {
    val pool = MemPool.empty(GroupIndex(0))
    pool.size is 0
  }

  it should "contain/add/remove for transactions" in {
    forAll(ModelGen.blockGenNonEmpty) { block =>
      val group = GroupIndex(config.brokerInfo.groupFrom + Random.nextInt(config.groupNumPerBroker))
      val pool  = MemPool.empty(group)
      val index = block.chainIndex
      if (index.from.equals(group)) {
        block.transactions.foreach(pool.contains(index, _) is false)
        pool.add(index, block.transactions) is block.transactions.length
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
    val group = GroupIndex(0)
    val pool  = MemPool.empty(group)
    val block = ModelGen.blockGenFrom(group).retryUntil(_.transactions.nonEmpty).sample.get
    val txNum = block.transactions.length
    val rwl   = pool._getLock

    val chainIndex   = block.chainIndex
    val sizeAfterAdd = if (txNum >= 3) 3 else txNum
  }

  it should "use read lock for contains" in new Fixture {
    checkReadLock(rwl)(true, pool.contains(chainIndex, block.transactions.head), false)
  }

  it should "use read lock for add" in new Fixture {
    checkLockUsed(rwl)(0, pool.add(chainIndex, block.transactions), txNum)
    pool.clear()
    checkNoWriteLock(rwl)(0, pool.add(chainIndex, block.transactions), txNum)
  }

  it should "use read lock for remove" in new Fixture {
    checkReadLock(rwl)(1, pool.remove(chainIndex, block.transactions), 0)
  }

  it should "use write lock for reorg" in new Fixture {
    val foo = AVector.fill(config.groups)(AVector.empty[Transaction])
    checkWriteLock(rwl)((1, 1), pool.reorg(foo, foo), (0, 0))
  }
}
