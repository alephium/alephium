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
import org.alephium.protocol.model._
import org.alephium.protocol.vm.GasPrice
import org.alephium.util.{AVector, LockFixture, TimeStamp, UnsecureRandom}

class MemPoolSpec
    extends AlephiumFlowSpec
    with TxIndexesSpec.Fixture
    with LockFixture
    with NoIndexModelGeneratorsLike {
  def now = TimeStamp.now()

  val mainGroup      = GroupIndex.unsafe(0)
  val emptyTxIndexes = TxIndexes.emptyMemPool(mainGroup)

  it should "initialize an empty pool" in {
    val pool = MemPool.empty(mainGroup)
    pool.size is 0
  }

  it should "contain/add/remove for transactions" in {
    forAll(blockGen) { block =>
      val txTemplates = block.transactions.map(_.toTemplate)
      val group       = GroupIndex.unsafe(UnsecureRandom.sample(brokerConfig.groupRange))
      val pool        = MemPool.empty(group)
      val index       = block.chainIndex
      if (index.from.equals(group)) {
        txTemplates.foreach(pool.contains(_) is false)
        pool.add(index, txTemplates, now) is block.transactions.length
        pool.size is block.transactions.length
        block.transactions.foreach(tx => checkTx(pool.sharedTxIndexes, tx.toTemplate))
        txTemplates.foreach(pool.contains(_) is true)
        pool.removeUsedTxs(txTemplates) is block.transactions.length
        pool.size is 0
        pool.sharedTxIndexes is emptyTxIndexes
      }
    }
  }

  it should "calculate the size of mempool" in {
    val pool = MemPool.empty(mainGroup)
    val tx0  = transactionGen().sample.get.toTemplate
    pool.add(ChainIndex.unsafe(0, 0), tx0, TimeStamp.now())
    pool.size is 1
    val tx1 = transactionGen().sample.get.toTemplate
    pool.add(ChainIndex.unsafe(0, 1), tx1, now)
    pool.size is 2
  }

  it should "check capacity" in {
    val pool       = MemPool.ofCapacity(mainGroup, 1)
    val tx0        = transactionGen().sample.get.toTemplate
    val chainIndex = ChainIndex.unsafe(0, 0)
    pool.add(chainIndex, tx0, TimeStamp.now()) is MemPool.AddedToMemPool
    pool.isFull() is true
    pool.contains(tx0.id) is true

    val higherGasPrice = GasPrice(tx0.unsigned.gasPrice.value.addUnsafe(1))
    val tx1            = tx0.copy(unsigned = tx0.unsigned.copy(gasPrice = higherGasPrice))
    pool.add(chainIndex, tx1, TimeStamp.now()) is MemPool.AddedToMemPool
    pool.isFull() is true
    pool.contains(tx0.id) is false
    pool.contains(tx1.id) is true

    pool.add(chainIndex, tx0, TimeStamp.now()) is MemPool.MemPoolIsFull
    pool.isFull() is true
    pool.contains(tx0.id) is false
    pool.contains(tx1.id) is true
  }

  trait Fixture {
    val pool   = MemPool.empty(GroupIndex.unsafe(0))
    val index0 = ChainIndex.unsafe(0, 0)
    val index1 = ChainIndex.unsafe(0, 1)
    val tx0    = transactionGen().retryUntil(_.chainIndex equals index0).sample.get.toTemplate
    val tx1    = transactionGen().retryUntil(_.chainIndex equals index1).sample.get.toTemplate
    pool.add(index0, tx0, TimeStamp.now())
    pool.add(index1, tx1, now)
  }

  it should "list transactions for a specific group" in new Fixture {
    pool.getAll().map(_.id).toSet is AVector(tx0, tx1).map(_.id).toSet
  }

  it should "work for utxos" in new Fixture {
    tx0.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    tx1.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    pool.isDoubleSpending(index0, tx0) is true
    pool.isDoubleSpending(index0, tx1) is true
    tx0.assetOutputRefs.foreach(output =>
      pool.sharedTxIndexes.outputIndex.contains(output) is
        (output.fromGroup equals mainGroup)
    )
    tx1.assetOutputRefs.foreach(output =>
      pool.sharedTxIndexes.outputIndex.contains(output) is
        (output.fromGroup equals mainGroup)
    )
    tx0.assetOutputRefs.foreachWithIndex((output, index) =>
      if (output.fromGroup equals mainGroup) {
        pool.getOutput(output) is Some(tx0.getOutput(index))
      }
    )
    tx1.assetOutputRefs.foreachWithIndex((output, index) =>
      if (output.fromGroup equals mainGroup) {
        pool.getOutput(output) is Some(tx1.getOutput(index))
      }
    )
  }

  it should "work for sequential txs for intra-group chain" in new Fixture {
    val blockFlow  = isolatedBlockFlow()
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block0     = transfer(blockFlow, chainIndex)
    val tx2        = block0.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, chainIndex)
    val tx3    = block1.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow, block1)

    pool.add(chainIndex, tx2, TimeStamp.now())
    pool.add(chainIndex, tx3, now)
    val tx2Outputs = tx2.assetOutputRefs
    tx2Outputs.length is 2
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.head) is true
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.last) is true
    pool.isSpent(tx2Outputs.last) is true
    tx3.assetOutputRefs.foreach(output => pool.isSpent(output) is false)
  }

  it should "work for sequential txs for inter-group chain" in new Fixture {
    val blockFlow  = isolatedBlockFlow()
    val chainIndex = ChainIndex.unsafe(0, 2)
    val block0     = transfer(blockFlow, chainIndex)
    val tx2        = block0.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, chainIndex)
    val tx3    = block1.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow, block1)

    pool.add(chainIndex, tx2, TimeStamp.now())
    pool.add(chainIndex, tx3, now)
    val tx2Outputs = tx2.assetOutputRefs
    tx2Outputs.length is 2
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.head) is false
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.last) is true
    pool.isSpent(tx2Outputs.last) is true
    tx3.assetOutputRefs.foreach { output =>
      if (output.fromGroup.value == 0) {
        pool.isSpent(output) is false
      } else {
        pool.sharedTxIndexes.outputIndex.contains(output) is false
      }
    }
  }

  it should "clean mempool" in {
    val blockFlow = isolatedBlockFlow()

    val pool   = MemPool.empty(mainGroup)
    val index0 = ChainIndex.unsafe(0, 0)
    val index1 = ChainIndex.unsafe(0, 1)
    val index2 = ChainIndex.unsafe(0, 2)
    val tx0    = transactionGen().retryUntil(_.chainIndex equals index0).sample.get.toTemplate
    val tx1    = transactionGen().retryUntil(_.chainIndex equals index1).sample.get.toTemplate
    val block2 = transfer(blockFlow, index2)
    val tx2    = block2.nonCoinbase.head.toTemplate
    val tx3 =
      tx2.copy(unsigned = tx2.unsigned.copy(inputs = tx2.unsigned.inputs ++ tx1.unsigned.inputs))

    blockFlow.recheckInputs(index2.from, AVector(tx2, tx3)) isE AVector(tx3)

    val currentTs = TimeStamp.now()
    pool.add(index0, tx0, currentTs) is MemPool.AddedToMemPool
    pool.size is 1
    pool.add(index1, tx1, currentTs) is MemPool.AddedToMemPool
    pool.size is 2
    pool.add(index2, tx2, currentTs) is MemPool.AddedToMemPool
    pool.size is 3
    pool.add(index2, tx3, currentTs) is MemPool.DoubleSpending
    pool.size is 3
    pool.clean(blockFlow, TimeStamp.now().plusMinutesUnsafe(1))
    pool.size is 1
    pool.contains(tx2) is true
  }

  it should "clear mempool" in new Fixture {
    tx0.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    pool.clear()
    tx0.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is false)
  }

  it should "collect transactions based on gas price" in {
    val pool = MemPool.empty(mainGroup)
    pool.size is 0

    val index = ChainIndex.unsafe(0)
    val txs = Seq.tabulate(10) { k =>
      val tx = transactionGen().sample.get
      tx.copy(unsigned = tx.unsigned.copy(gasPrice = GasPrice(defaultGasPrice.value + k)))
        .toTemplate
    }
    val timeStamp = TimeStamp.now()
    Random.shuffle(txs).foreach(tx => pool.add(index, tx, timeStamp))

    pool.collectForBlock(index, Int.MaxValue) is AVector.from(
      txs.sortBy(_.unsigned.gasPrice.value).reverse
    )
  }
}
