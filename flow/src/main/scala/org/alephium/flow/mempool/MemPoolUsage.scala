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

import org.alephium.protocol.vm.LockupScript

final private[mempool] case class MemPoolPayerKey(
    chainIndex: Int,
    feePayer: LockupScript.Asset
)

final private[mempool] class MemPoolUsage(
    var transactionCount: Int,
    var gas: Long,
    var oversizedTransactionCount: Int
) {
  def this() = this(0, 0L, 0)

  def add(txGas: Long, isOversized: Boolean): Unit = {
    transactionCount += 1
    gas += txGas
    if (isOversized) oversizedTransactionCount += 1
  }

  def remove(txGas: Long, isOversized: Boolean): Unit = {
    transactionCount -= 1
    gas -= txGas
    if (isOversized) oversizedTransactionCount -= 1
    assume(transactionCount >= 0 && gas >= 0 && oversizedTransactionCount >= 0)
  }

  def clear(): Unit = {
    transactionCount = 0
    gas = 0L
    oversizedTransactionCount = 0
  }

  def snapshot: MemPool.UsageSnapshot =
    MemPool.UsageSnapshot(transactionCount, gas, oversizedTransactionCount)
}

private[mempool] object MemPoolUsage {
  def empty: MemPoolUsage = new MemPoolUsage()
}
