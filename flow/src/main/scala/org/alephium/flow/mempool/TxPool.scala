package org.alephium.flow.mempool

import scala.collection.mutable

import org.alephium.flow.mempool.TxPool.WeightedId
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.Transaction
import org.alephium.util.{AVector, RWLock}

/*
 * Transaction pool implementation
 */
class TxPool private (pool: mutable.SortedMap[WeightedId, Transaction],
                      weights: mutable.HashMap[Hash, Double],
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

  final case class WeightedId(weight: Double, id: Hash) {
    override def equals(obj: Any): Boolean = obj match {
      case that: WeightedId => this.id == that.id
      case _                => false
    }

    override def hashCode(): Int = id.hashCode()
  }

  implicit val ord: Ordering[WeightedId] = {
    import scala.math.Ordering.Implicits.seqOrdering
    implicit val keccak256Ord: Ordering[Hash]      = Ordering.by[Hash, Seq[Byte]](_.bytes.toSeq)
    implicit val pairOrd: Ordering[(Double, Hash)] = Ordering.Tuple2[Double, Hash]
    Ordering.by(p => (-p.weight, p.id))
  }
}
