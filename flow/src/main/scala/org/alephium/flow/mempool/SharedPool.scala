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
    val chainIndex: ChainIndex,
    val pool: mutable.SortedMap[WeightedId, TransactionTemplate],
    val weights: mutable.HashMap[Hash, GasPrice],
    val sharedTxIndex: TxIndexes,
    val capacity: Int
) extends RWLock {

  def isFull: Boolean = pool.size == capacity

  def size: Int = pool.size

  def contains(txId: Hash): Boolean = readOnly {
    weights.contains(txId)
  }

  def collectForBlock(maxNum: Int): AVector[TransactionTemplate] = readOnly {
    AVector.from(pool.values.take(maxNum))
  }

  def getAll(): AVector[TransactionTemplate] = readOnly {
    AVector.from(pool.values)
  }

  def add(transactions: AVector[TransactionTemplate]): Int = writeOnly {
    val result = transactions.fold(0) { case (acc, tx) =>
      acc + _add(tx)
    }
    measureTransactionsTotal()
    result
  }

  def add(tx: TransactionTemplate): Boolean = writeOnly {
    _add(tx) != 0
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def _add(tx: TransactionTemplate): Int = {
    val txGasPrice = tx.unsigned.gasPrice
    val txWeight   = WeightedId(txGasPrice, tx.id)
    if (isFull) {
      val lowestWeight = pool.head._1
      if (txGasPrice > lowestWeight.gasPrice) {
        _remove(lowestWeight.id)
        _add(txWeight, tx)
        1
      } else {
        0
      }
    } else {
      _add(txWeight, tx)
      1
    }
  }

  def _add(weightedId: WeightedId, tx: TransactionTemplate): Unit = {
    weights += tx.id   -> tx.unsigned.gasPrice
    pool += weightedId -> tx
    sharedTxIndex.add(tx)
  }

  def remove(transactions: AVector[TransactionTemplate]): Int = writeOnly {
    val sizeBefore = size
    transactions.foreach(tx => _remove(tx.id))
    measureTransactionsTotal()
    val sizeAfter = size
    sizeBefore - sizeAfter
  }

  def _remove(txId: Hash): Unit = {
    weights.get(txId).foreach { weight =>
      val weightedId = WeightedId(weight, txId)
      val tx         = pool(weightedId)
      weights -= txId
      pool -= weightedId
      sharedTxIndex.remove(tx)
    }
  }

  def clear(): Unit = writeOnly {
    pool.clear()
  }

  private val transactionsTotalLabeled = MemPool.sharedPoolTransactionsTotal
    .labels(chainIndex.from.value.toString, chainIndex.to.value.toString)
  def measureTransactionsTotal(): Unit = {
    transactionsTotalLabeled.set(size.toDouble)
  }
}

object SharedPool {
  def empty(chainIndex: ChainIndex, capacity: Int, sharedTxIndex: TxIndexes): SharedPool =
    new SharedPool(
      chainIndex,
      mutable.SortedMap.empty,
      mutable.HashMap.empty,
      sharedTxIndex,
      capacity
    )

  final case class WeightedId(gasPrice: GasPrice, id: Hash) {
    override def equals(obj: Any): Boolean =
      obj match {
        case that: WeightedId => this.id == that.id
        case _                => false
      }

    override def hashCode(): Int = id.hashCode()
  }

  implicit val ord: Ordering[WeightedId] = {
    Ordering
      .by[WeightedId, (U256, Hash)](weightedId => (weightedId.gasPrice.value, weightedId.id))
      .reverse
  }
}
