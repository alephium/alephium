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

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model.NoIndexModelGeneratorsLike
import org.alephium.util.TimeStamp

class TxHandlerBufferSpec
    extends AlephiumFlowSpec
    with TxIndexesSpec.Fixture
    with NoIndexModelGeneratorsLike {
  override val configValues = Map(("alephium.broker.broker-num", 1))

  it should "work for parallel transactions" in {
    val block = blockGen.sample.get
    val txs   = block.transactions.map(_.toTemplate)
    val pool  = TxHandlerBuffer.default()
    txs.foreach(pool.add(_, TimeStamp.now()))
    pool.getRootTxs().toSet is txs.toSet
    pool.size is txs.length

    val toRemove0 = txs.head
    pool.removeValidTx(toRemove0) is None
    pool.getRootTxs().toSet is txs.tail.toSet

    val toRemove1 = txs.last
    pool.removeInvalidTx(toRemove1)
    pool.getRootTxs().toSet is txs.tail.init.toSet

    pool.clean(TimeStamp.now().plusHoursUnsafe(1)) is (txs.length - 2)
    pool.getRootTxs().isEmpty is true

    pool.clear()
    pool.size is 0
  }

  trait SequentialFixture {
    brokerConfig.brokerNum is 1

    val txs  = prepareRandomSequentialTxs(4).map(_.toTemplate)
    val pool = TxHandlerBuffer.default()
    txs.foreach(pool.add(_, TimeStamp.now()))
    pool.getRootTxs().toSet is Set(txs.head)
    pool.size is txs.length

    val toRemove = txs.head
  }

  it should "remove valid sequential transactions" in new SequentialFixture {
    pool.removeValidTx(toRemove).value.toSeq is Seq(txs(1))
    pool.getRootTxs().toSet is Set(txs(1))
  }

  it should "remove invalid sequential transactions" in new SequentialFixture {
    pool.removeInvalidTx(toRemove)
    pool.getRootTxs().isEmpty is true
  }

  it should "check capacity" in {
    val pool = TxHandlerBuffer.ofCapacity(1)
    val tx0  = transactionGen().sample.get.toTemplate
    val tx1  = transactionGen().sample.get.toTemplate
    pool.add(tx0, TimeStamp.now())
    pool.add(tx1, TimeStamp.now())
    pool.getRootTxs().length is 1

    pool.clear()
    pool.size is 0
  }
}
