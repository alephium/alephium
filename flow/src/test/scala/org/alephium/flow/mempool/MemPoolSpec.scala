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

import scala.util.Random

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model.{GroupIndex, NoIndexModelGeneratorsLike}
import org.alephium.util.LockFixture

class MemPoolSpec
    extends AlephiumFlowSpec
    with TxIndexesSpec.Fixture
    with LockFixture
    with NoIndexModelGeneratorsLike {
  it should "initialize an empty pool" in {
    val pool = MemPool.empty(GroupIndex.unsafe(0))
    pool.size is 0
  }

  it should "contain/add/remove for transactions" in {
    forAll(blockGen) { block =>
      val txTemplates = block.transactions.map(_.toTemplate)
      val group =
        GroupIndex.unsafe(
          brokerConfig.groupFrom + Random.nextInt(brokerConfig.groupNumPerBroker)
        )
      val pool  = MemPool.empty(group)
      val index = block.chainIndex
      if (index.from.equals(group)) {
        txTemplates.foreach(pool.contains(index, _) is false)
        pool.addToTxPool(index, txTemplates) is block.transactions.length
        pool.size is block.transactions.length
        block.transactions.foreach(checkTx(pool.txIndexes, _))
        txTemplates.foreach(pool.contains(index, _) is true)
        pool.removeFromTxPool(index, txTemplates) is block.transactions.length
        pool.size is 0
        pool.txIndexes is TxIndexes.emptySharedPool
      } else {
        assertThrows[AssertionError](txTemplates.foreach(pool.contains(index, _)))
      }
    }
  }
}
