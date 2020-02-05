package org.alephium.flow.core.mempool

import scala.collection.mutable

import org.alephium.crypto.Keccak256
import org.alephium.flow.RWLock
import org.alephium.protocol.model.Transaction
import org.alephium.util.AVector

/*
 * Transaction pool implementation
 */
class TxPool private (pool: mutable.HashMap[Keccak256, Transaction], _capacity: Int)
    extends RWLock {
  def isFull: Boolean = pool.size == _capacity

  def size: Int = pool.size

  def capacity: Int = _capacity

  def contains(transaction: Transaction): Boolean = readOnly {
    pool.contains(transaction.hash)
  }

  def add(transactions: AVector[Transaction]): Int = {
    update(transactions) { tx =>
      if (isFull) false
      else {
        pool += tx.hash -> tx
        true
      }
    }
  }

  def remove(transactions: AVector[Transaction]): Int = {
    val count = update(transactions) { tx =>
      if (pool.contains(tx.hash)) {
        pool -= tx.hash
        true
      } else false
    }
    -count
  }

  def update(transactions: AVector[Transaction])(f: Transaction => Boolean): Int = writeOnly {
    val sizeBefore = size
    transactions.foreachE { tx =>
      val result = f(tx)
      if (!result) Left(()) else Right(())
    }
    val sizeAfter = size
    sizeAfter - sizeBefore
  }

  def clear(): Unit = writeOnly {
    pool.clear()
  }
}

object TxPool {
  def empty(capacity: Int): TxPool = new TxPool(mutable.HashMap.empty, capacity)
}
