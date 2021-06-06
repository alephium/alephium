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

import org.alephium.flow.mempool.SharedPool.WeightedId
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.protocol.vm.GasPrice
import org.alephium.util.{AVector, RWLock, U256}

/*
 * Transaction pool implementation
 */
class SharedPool private (
    chainIndex: ChainIndex,
    pool: mutable.SortedMap[WeightedId, TransactionTemplate],
    weights: mutable.HashMap[Hash, GasPrice],
    val capacity: Int
) extends Pool
    with RWLock {

  def isFull: Boolean = pool.size == capacity

  def size: Int = pool.size

  def contains(txId: Hash): Boolean =
    readOnly {
      weights.contains(txId)
    }

  def collectForBlock(maxNum: Int): AVector[TransactionTemplate] =
    readOnly {
      AVector.from(pool.values.take(maxNum))
    }

  def getAll(): AVector[TransactionTemplate] =
    readOnly {
      AVector.from(pool.values)
    }

  def add(transactions: AVector[TransactionTemplate]): Int =
    writeOnly {
      val sizeBefore = size
      transactions.foreachE { case (tx) =>
        if (isFull) {
          Left(())
        } else {
          weights += tx.id                                -> tx.unsigned.gasPrice
          pool += WeightedId(tx.unsigned.gasPrice, tx.id) -> tx
          Right(())
        }
      }
      val sizeAfter = size
      sizeAfter - sizeBefore
    }

  def remove(transactions: AVector[TransactionTemplate]): Int =
    writeOnly {
      val sizeBefore = size
      transactions.foreach { tx =>
        if (contains(tx.id)) {
          val weight = weights(tx.id)
          weights -= tx.id
          pool -= WeightedId(weight, tx.id)
        }
      }
      val sizeAfter = size
      sizeBefore - sizeAfter
    }

  def clear(): Unit =
    writeOnly {
      pool.clear()
    }

  private val transactionsTotalLabeled = MemPool.sharedPoolTransactionsTotal
    .labels(chainIndex.from.value.toString, chainIndex.to.value.toString)
  def measureTransactionsTotal(): Unit = {
    transactionsTotalLabeled.set(size.toDouble)
  }
}

object SharedPool {
  def empty(chainIndex: ChainIndex, capacity: Int): SharedPool =
    new SharedPool(chainIndex, mutable.SortedMap.empty, mutable.HashMap.empty, capacity)

  final case class WeightedId(weight: GasPrice, id: Hash) {
    override def equals(obj: Any): Boolean =
      obj match {
        case that: WeightedId => this.id == that.id
        case _                => false
      }

    override def hashCode(): Int = id.hashCode()
  }

  implicit val ord: Ordering[WeightedId] = {
    Ordering
      .by[WeightedId, (U256, Hash)](weightedId => (weightedId.weight.value, weightedId.id))
      .reverse
  }
}
