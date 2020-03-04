package org.alephium.flow.core.mempool

import scala.collection.mutable

import org.alephium.crypto.Keccak256
import org.alephium.flow.core.mempool.TxPool.WeightedId
import org.alephium.protocol.model.Transaction
import org.alephium.util.{AVector, RWLock}

/*
 * Transaction pool implementation
 */
class TxPool private (pool: mutable.SortedMap[WeightedId, Transaction],
                      weights: mutable.HashMap[Keccak256, Double],
                      _capacity: Int)
    extends RWLock {
  def isFull: Boolean = pool.size == _capacity

  def size: Int = pool.size

  def capacity: Int = _capacity

  def contains(transaction: Transaction): Boolean = readOnly {
    weights.contains(transaction.hash)
  }

  def collectForBlock(maxNum: Int): AVector[Transaction] = readOnly {
    AVector.from(pool.values.take(maxNum))
  }

  def add(transactions: AVector[(Transaction, Double)]): Int = writeOnly {
    val sizeBefore = size
    transactions.foreachE {
      case (tx, weight) =>
        if (isFull) Left(())
        else {
          weights += tx.hash                  -> weight
          pool += WeightedId(weight, tx.hash) -> tx
          Right(())
        }
    }
    val sizeAfter = size
    sizeAfter - sizeBefore
  }

  def remove(transactions: AVector[Transaction]): Int = writeOnly {
    val sizeBefore = size
    transactions.foreach { tx =>
      if (contains(tx)) {
        val weight = weights(tx.hash)
        weights -= tx.hash
        pool -= WeightedId(weight, tx.hash)
      }
    }
    val sizeAfter = size
    sizeBefore - sizeAfter
  }

  def clear(): Unit = writeOnly {
    pool.clear()
  }
}

object TxPool {
  def empty(capacity: Int): TxPool =
    new TxPool(mutable.SortedMap.empty, mutable.HashMap.empty, capacity)

  final case class WeightedId(weight: Double, id: Keccak256) {
    override def equals(obj: Any): Boolean = obj match {
      case that: WeightedId => this.id == that.id
      case _                => false
    }

    override def hashCode(): Int = id.hashCode()
  }

  implicit val ord: Ordering[WeightedId] = {
    implicit val keccak256Ord: Ordering[Keccak256]      = Ordering.Iterable[Byte].on[Keccak256](_.bytes)
    implicit val pairOrd: Ordering[(Double, Keccak256)] = Ordering.Tuple2[Double, Keccak256]
    Ordering.by(p => (-p.weight, p.id))
  }
}
