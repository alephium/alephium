package org.alephium.flow.core.mempool

import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.{ChainIndex, GroupIndex, Transaction}
import org.alephium.util.{AVector, RWLock}

/*
 * MemPool is the class to store all the pending transactions
 *
 * Transactions should be ordered according to weights. The weight is calculated based on fees
 */
class MemPool private (group: GroupIndex, pools: AVector[TxPool])(implicit config: PlatformProfile)
    extends RWLock {
  def getPool(index: ChainIndex): TxPool = {
    assume(group == index.from)
    pools(index.to.value)
  }

  def size: Int = pools.sumBy(_.size)

  def contains(index: ChainIndex, transaction: Transaction): Boolean = readOnly {
    getPool(index).contains(transaction)
  }

  def collectForBlock(index: ChainIndex, maxNum: Int): AVector[Transaction] = readOnly {
    getPool(index).collectForBlock(maxNum)
  }

  def add(index: ChainIndex, transactions: AVector[(Transaction, Double)]): Int = readOnly {
    getPool(index).add(transactions)
  }

  def remove(index: ChainIndex, transactions: AVector[Transaction]): Int = readOnly {
    getPool(index).remove(transactions)
  }

  // Note: we lock the mem pool so that we could update all the transaction pools
  def reorg(toRemove: AVector[AVector[Transaction]],
            toAdd: AVector[AVector[(Transaction, Double)]]): (Int, Int) = writeOnly {
    assume(toRemove.length == config.groups && toAdd.length == config.groups)

    // First, add transactions from short chains, then remove transactions from canonical chains
    val added   = toAdd.foldWithIndex(0)((sum, txs, toGroup)    => sum + pools(toGroup).add(txs))
    val removed = toRemove.foldWithIndex(0)((sum, txs, toGroup) => sum + pools(toGroup).remove(txs))
    (removed, added)
  }

  def clear(): Unit = writeOnly {
    pools.foreach(_.clear())
  }
}

object MemPool {
  def empty(groupIndex: GroupIndex)(implicit config: PlatformProfile): MemPool = {
    val pools = AVector.fill(config.groups)(TxPool.empty(config.txPoolCapacity))
    new MemPool(groupIndex, pools)
  }
}
