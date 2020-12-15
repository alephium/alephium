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

import scala.collection.mutable

import org.alephium.flow.mempool.TxPool.WeightedId
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{TransactionAbstract, TransactionTemplate}
import org.alephium.util.{AVector, RWLock}

/*
 * Transaction pool implementation
 */
class TxPool private (pool: mutable.SortedMap[WeightedId, TransactionTemplate],
                      weights: mutable.HashMap[Hash, Double],
                      _capacity: Int)
    extends RWLock {
  def isFull: Boolean = pool.size == _capacity

  def size: Int = pool.size

  def capacity: Int = _capacity

  def contains(transaction: TransactionAbstract): Boolean = readOnly {
    weights.contains(transaction.id)
  }

  def collectForBlock(maxNum: Int): AVector[TransactionTemplate] = readOnly {
    AVector.from(pool.values.take(maxNum))
  }

  def getAll: AVector[TransactionTemplate] = readOnly {
    AVector.from(pool.values)
  }

  def add(transactions: AVector[(TransactionTemplate, Double)]): Int = writeOnly {
    val sizeBefore = size
    transactions.foreachE {
      case (tx, weight) =>
        if (isFull) {
          Left(())
        } else {
          weights += tx.id                  -> weight
          pool += WeightedId(weight, tx.id) -> tx
          Right(())
        }
    }
    val sizeAfter = size
    sizeAfter - sizeBefore
  }

  def remove(transactions: AVector[TransactionTemplate]): Int = writeOnly {
    val sizeBefore = size
    transactions.foreach { tx =>
      if (contains(tx)) {
        val weight = weights(tx.id)
        weights -= tx.id
        pool -= WeightedId(weight, tx.id)
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
