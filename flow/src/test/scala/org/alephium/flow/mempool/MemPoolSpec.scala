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
import org.alephium.flow.model.MempoolTxMetadata
import org.alephium.flow.setting.MemPoolAdmissionLimits
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.util.{AVector, Duration, LockFixture, TimeStamp, UnsecureRandom}

class MemPoolSpec
    extends AlephiumFlowSpec
    with TxIndexesSpec.Fixture
    with LockFixture
    with NoIndexModelGeneratorsLike {
  def now = TimeStamp.now()

  val mainGroup      = GroupIndex.unsafe(0)
  val emptyTxIndexes = TxIndexes.emptyMemPool(mainGroup)
  val admissionLimits = MemPoolAdmissionLimits(
    enabled = true,
    maxGasPerChainInBlocks = 4,
    maxTransactionsPerFeePayerPercent = 25,
    maxGasPerFeePayerPercentOfBlock = 20
  )

  private def admissionTx(
      index: ChainIndex,
      gas: Int,
      gasPriceDelta: Int = 0
  ): TransactionTemplate = {
    val tx = transactionGen().retryUntil(_.chainIndex == index).sample.get.toTemplate
    tx.copy(unsigned =
      tx.unsigned.copy(
        gasAmount = GasBox.unsafeTest(gas),
        gasPrice = GasPrice(nonCoinbaseMinGasPrice.value + gasPriceDelta)
      )
    )
  }

  private def admissionMetadata(
      payer: LockupScript.Asset,
      maximalGasPerBlock: Int = 1000
  ): MempoolTxMetadata = {
    MempoolTxMetadata(Some(payer), GasBox.unsafeTest(maximalGasPerBlock))
  }

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
    val now0       = TimeStamp.now()
    pool.add(chainIndex, tx0, now0) is MemPool.AddedToMemPool(now0)
    pool.isFull() is true
    pool.contains(tx0.id) is true

    val higherGasPrice = GasPrice(tx0.unsigned.gasPrice.value.addUnsafe(1))
    val tx1            = tx0.copy(unsigned = tx0.unsigned.copy(gasPrice = higherGasPrice))
    val now1           = TimeStamp.now()
    pool.add(chainIndex, tx1, now1) is MemPool.AddedToMemPool(now1)
    pool.isFull() is true
    pool.contains(tx0.id) is false
    pool.contains(tx1.id) is true

    pool.add(chainIndex, tx0, TimeStamp.now()) is MemPool.MemPoolIsFull
    pool.isFull() is true
    pool.contains(tx0.id) is false
    pool.contains(tx1.id) is true
  }

  it should "enforce the transaction limit per fee payer" in {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val pool       = MemPool.ofCapacity(mainGroup, 20, admissionLimits)
    val payer      = assetLockupGen(chainIndex.from).sample.get
    val metadata   = admissionMetadata(payer)
    val txs        = AVector.tabulate(6)(_ => admissionTx(chainIndex, gas = 1))
    val timestamp  = TimeStamp.now()

    txs.take(5).foreach(tx => pool.add(chainIndex, tx, timestamp, metadata).addedCount is 1)
    pool.getFeePayerUsage(chainIndex, payer) is MemPool.UsageSnapshot(5, 5L, 0)
    pool.add(chainIndex, txs.last, timestamp, metadata) is
      MemPool.FeePayerLimitExceeded(MemPool.FeePayerLimitReason.TransactionCount)

    pool.removeUnusedTx(txs.head)
    pool.add(chainIndex, txs.last, timestamp, metadata).addedCount is 1
    pool.getFeePayerUsage(chainIndex, payer) is MemPool.UsageSnapshot(5, 5L, 0)

    pool.clear()
    pool.getFeePayerUsage(chainIndex, payer) is MemPool.UsageSnapshot.empty
    pool.getChainUsage(chainIndex) is MemPool.UsageSnapshot.empty
  }

  it should "allow a single oversized transaction per fee payer" in {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val pool       = MemPool.ofCapacity(mainGroup, 20, admissionLimits)
    val payer      = assetLockupGen(chainIndex.from).sample.get
    val metadata   = admissionMetadata(payer)
    val timestamp  = TimeStamp.now()
    val largeTx    = admissionTx(chainIndex, gas = 500)
    val normalTx   = admissionTx(chainIndex, gas = 1, gasPriceDelta = 1)

    pool.add(chainIndex, largeTx, timestamp, metadata).addedCount is 1
    pool.getFeePayerUsage(chainIndex, payer) is MemPool.UsageSnapshot(1, 500L, 1)
    pool.add(chainIndex, normalTx, timestamp, metadata) is
      MemPool.FeePayerLimitExceeded(MemPool.FeePayerLimitReason.Gas)

    pool.removeUnusedTx(largeTx)
    pool.getFeePayerUsage(chainIndex, payer) is MemPool.UsageSnapshot.empty
    pool.add(chainIndex, normalTx, timestamp, metadata).addedCount is 1
    pool.add(chainIndex, largeTx, timestamp, metadata) is
      MemPool.FeePayerLimitExceeded(MemPool.FeePayerLimitReason.Gas)
  }

  it should "enforce the normal gas limit per fee payer" in {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val pool       = MemPool.ofCapacity(mainGroup, 20, admissionLimits)
    val payer      = assetLockupGen(chainIndex.from).sample.get
    val metadata   = admissionMetadata(payer)
    val timestamp  = TimeStamp.now()
    val tx0        = admissionTx(chainIndex, gas = 100)
    val tx1        = admissionTx(chainIndex, gas = 100)
    val tx2        = admissionTx(chainIndex, gas = 1, gasPriceDelta = 10)

    pool.add(chainIndex, tx0, timestamp, metadata).addedCount is 1
    pool.add(chainIndex, tx1, timestamp, metadata).addedCount is 1
    pool.getFeePayerUsage(chainIndex, payer) is MemPool.UsageSnapshot(2, 200L, 0)
    pool.add(chainIndex, tx2, timestamp, metadata) is
      MemPool.FeePayerLimitExceeded(MemPool.FeePayerLimitReason.Gas)
  }

  it should "enforce per-chain gas limits without affecting other chains" in {
    val chain0    = ChainIndex.unsafe(0, 0)
    val chain1    = ChainIndex.unsafe(0, 1)
    val pool      = MemPool.ofCapacity(mainGroup, 20, 10, admissionLimits)
    val timestamp = TimeStamp.now()
    val chain0Txs = AVector.tabulate(4) { index =>
      val payer = assetLockupGen(chain0.from).sample.get
      val tx    = admissionTx(chain0, gas = 1000, gasPriceDelta = index)
      pool.add(chain0, tx, timestamp, admissionMetadata(payer)).addedCount is 1
      tx
    }
    val chain1Tx = admissionTx(chain1, gas = 1)
    pool
      .add(
        chain1,
        chain1Tx,
        timestamp,
        admissionMetadata(assetLockupGen(chain1.from).sample.get)
      )
      .addedCount is 1

    pool.getChainUsage(chain0) is MemPool.UsageSnapshot(4, 4000L, 4)
    pool.getChainUsage(chain1) is MemPool.UsageSnapshot(1, 1L, 0)

    val replacement = admissionTx(chain0, gas = 1000, gasPriceDelta = 10)
    pool
      .add(
        chain0,
        replacement,
        timestamp,
        admissionMetadata(assetLockupGen(chain0.from).sample.get)
      )
      .addedCount is 1
    pool.contains(chain0Txs.head) is false
    pool.contains(chain1Tx) is true
    pool.getChainUsage(chain0) is MemPool.UsageSnapshot(4, 4000L, 4)
  }

  it should "enforce per-chain transaction limits without affecting other chains" in {
    val chain0    = ChainIndex.unsafe(0, 0)
    val chain1    = ChainIndex.unsafe(0, 1)
    val pool      = MemPool.ofCapacity(mainGroup, 8, 4, admissionLimits)
    val timestamp = TimeStamp.now()
    val chain0Txs = AVector.tabulate(4) { index =>
      val payer = assetLockupGen(chain0.from).sample.get
      val tx    = admissionTx(chain0, gas = 1, gasPriceDelta = index)
      pool.add(chain0, tx, timestamp, admissionMetadata(payer)).addedCount is 1
      tx
    }
    val chain1Tx = admissionTx(chain1, gas = 1)
    pool
      .add(
        chain1,
        chain1Tx,
        timestamp,
        admissionMetadata(assetLockupGen(chain1.from).sample.get)
      )
      .addedCount is 1

    val replacement = admissionTx(chain0, gas = 1, gasPriceDelta = 10)
    pool
      .add(
        chain0,
        replacement,
        timestamp,
        admissionMetadata(assetLockupGen(chain0.from).sample.get)
      )
      .addedCount is 1
    pool.contains(chain0Txs.head) is false
    pool.contains(chain1Tx) is true
    pool.getChainUsage(chain0) is MemPool.UsageSnapshot(4, 4L, 0)

    val lowFeeTx = admissionTx(chain0, gas = 1)
    pool.add(
      chain0,
      lowFeeTx,
      timestamp,
      admissionMetadata(assetLockupGen(chain0.from).sample.get)
    ) is MemPool.MemPoolIsFull
  }

  it should "not charge incoming cross-group transactions to source-chain limits" in {
    val chainIndex = ChainIndex.unsafe(1, 0)
    val pool       = MemPool.ofCapacity(mainGroup, 20, admissionLimits)
    val tx         = admissionTx(chainIndex, gas = 1000)
    val metadata   = MempoolTxMetadata(None, GasBox.unsafeTest(1000))

    pool.add(chainIndex, tx, TimeStamp.now(), metadata).addedCount is 1
    pool.getChainUsage(chainIndex) is MemPool.UsageSnapshot.empty
  }

  trait Fixture {
    val pool   = MemPool.empty(GroupIndex.unsafe(0))
    val index0 = ChainIndex.unsafe(0, 0)
    val index1 = ChainIndex.unsafe(0, 1)
    val tx0    = transactionGen().retryUntil(_.chainIndex equals index0).sample.get.toTemplate
    val tx1    = transactionGen().retryUntil(_.chainIndex equals index1).sample.get.toTemplate
    val ts0    = TimeStamp.now()
    val ts1    = ts0.plusUnsafe(Duration.ofSecondsUnsafe(1))
    pool.add(index0, tx0, ts0)
    pool.add(index1, tx1, ts1)
  }

  it should "list transactions for a specific group" in new Fixture {
    pool.getAll().map(_.id).toSet is AVector(tx0, tx1).map(_.id).toSet
    pool
      .getAllWithTimestamp()
      .map { case (template, ts) =>
        (template.id, ts)
      }
      .toSet is Set((tx0.id, ts0), (tx1.id, ts1))
  }

  it should "work for utxos" in new Fixture {
    tx0.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    tx1.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    pool.isDoubleSpending(index0, tx0) is true
    pool.isDoubleSpending(index0, tx1) is true
    tx0.fixedOutputRefs.foreach(output =>
      pool.sharedTxIndexes.outputIndex.contains(output) is
        (output.fromGroup equals mainGroup)
    )
    tx1.fixedOutputRefs.foreach(output =>
      pool.sharedTxIndexes.outputIndex.contains(output) is
        (output.fromGroup equals mainGroup)
    )
    tx0.fixedOutputRefs.foreachWithIndex((output, index) =>
      if (output.fromGroup equals mainGroup) {
        pool.getOutput(output) is Some(tx0.getOutput(index).asInstanceOf[AssetOutput])
      }
    )
    tx1.fixedOutputRefs.foreachWithIndex((output, index) =>
      if (output.fromGroup equals mainGroup) {
        pool.getOutput(output) is Some(tx1.getOutput(index).asInstanceOf[AssetOutput])
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
    val tx2Outputs = tx2.fixedOutputRefs
    tx2Outputs.length is 2
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.head) is true
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.last) is true
    pool.isSpent(tx2Outputs.last) is true
    tx3.fixedOutputRefs.foreach(output => pool.isSpent(output) is false)
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
    val tx2Outputs = tx2.fixedOutputRefs
    tx2Outputs.length is 2
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.head) is false
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.last) is true
    pool.isSpent(tx2Outputs.last) is true
    tx3.fixedOutputRefs.foreach { output =>
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
    pool.add(index0, tx0, currentTs) is MemPool.AddedToMemPool(currentTs)
    pool.size is 1
    pool.add(index1, tx1, currentTs) is MemPool.AddedToMemPool(currentTs)
    pool.size is 2
    pool.add(index2, tx2, currentTs) is MemPool.AddedToMemPool(currentTs)
    pool.size is 3
    pool.add(index2, tx3, currentTs) is MemPool.DoubleSpending
    pool.size is 3
    pool.cleanInvalidTxs(blockFlow, TimeStamp.now().plusMinutesUnsafe(1)) is 2
    pool.size is 1
    pool.contains(tx2) is true
  }

  it should "remove unconfirmed txs from mempool" in {
    val pool       = MemPool.empty(mainGroup)
    val chainIndex = ChainIndex.unsafe(0, 0)
    val txs0       = AVector.fill(3)(transactionGen().sample.get.toTemplate)
    val ts0        = TimeStamp.now()
    val txs1       = AVector.fill(3)(transactionGen().sample.get.toTemplate)
    val ts1        = TimeStamp.now().plusSecondsUnsafe(1)

    pool.add(chainIndex, txs0, ts0)
    pool.add(chainIndex, txs1, ts1)

    txs0.foreach(tx => pool.contains(tx.id) is true)
    txs1.foreach(tx => pool.contains(tx.id) is true)

    pool.cleanUnconfirmedTxs(ts0)
    txs0.foreach(tx => pool.contains(tx.id) is false)
    txs1.foreach(tx => pool.contains(tx.id) is true)
  }

  it should "clear mempool" in new Fixture {
    tx0.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    pool.clear()
    tx0.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is false)
  }

  it should "collect transactions based on gas price" in {
    val pool = MemPool.empty(mainGroup)
    pool.size is 0

    val index     = ChainIndex.unsafe(0)
    val txs       = Seq.tabulate(10)(k => genTx(GasPrice(nonCoinbaseMinGasPrice.value + k)))
    val timeStamp = TimeStamp.now()
    Random.shuffle(txs).foreach(tx => pool.add(index, tx, timeStamp))

    pool.collectNonSequentialTxs(index, Int.MaxValue) is AVector.from(
      txs.sortBy(_.unsigned.gasPrice.value).reverse
    )
  }

  it should "handle cross-group transactions" in {
    val mainGroup = GroupIndex.unsafe(0)
    val pool      = MemPool.empty(mainGroup)
    val index     = ChainIndex.unsafe(1, 0)
    val tx        = transactionGen().retryUntil(_.chainIndex == index).sample.get.toTemplate
    pool.addXGroupTx(index, tx, TimeStamp.now())
    pool.size is 1
    pool.collectNonSequentialTxs(ChainIndex(mainGroup, mainGroup), Int.MaxValue).isEmpty is true

    pool.cleanInvalidTxs(blockFlow, TimeStamp.now().plusHoursUnsafe(1)) is 1
    pool.size is 0
  }

  def genTx(gasPrice: GasPrice): TransactionTemplate = {
    val tx = transactionGen().sample.get
    tx.copy(unsigned = tx.unsigned.copy(gasPrice = gasPrice)).toTemplate
  }

  it should "sort txs by order" in {
    val pool         = MemPool.empty(mainGroup)
    val chainIndex   = ChainIndex.unsafe(0, 0)
    val txNum        = 10
    val now          = TimeStamp.now()
    val baseGasPrice = nonCoinbaseMinGasPrice
    val txs = AVector.tabulate(txNum) { index =>
      val ts = now.plusMillisUnsafe(index.toLong)
      val tx = genTx(GasPrice(baseGasPrice.value + index))
      pool.add(chainIndex, tx, ts)
      tx
    }

    val sorted0 = txs.reverse
    pool.flow.takeAllTxs(chainIndex.flattenIndex, Int.MaxValue) is sorted0

    val tx0 = genTx(GasPrice(baseGasPrice.value + 20))
    pool.add(chainIndex, tx0, now.plusMinutesUnsafe(1))
    pool.flow.takeAllTxs(chainIndex.flattenIndex, Int.MaxValue) is (tx0 +: sorted0)

    val tx1 = genTx(GasPrice(baseGasPrice.value + 40))
    pool.add(chainIndex, tx1, now.minusUnsafe(Duration.ofMinutesUnsafe(1)))
    pool.flow.takeAllTxs(chainIndex.flattenIndex, Int.MaxValue) is (AVector(tx1, tx0) ++ sorted0)

    val gasPrice = GasPrice(baseGasPrice.value - 10)
    val tx2      = genTx(gasPrice)
    pool.add(chainIndex, tx2, now)
    val sorted1 = AVector(tx1, tx0) ++ (sorted0 :+ tx2)
    pool.flow.takeAllTxs(chainIndex.flattenIndex, Int.MaxValue) is sorted1

    val tx3 = genTx(gasPrice)
    pool.add(chainIndex, tx3, now.minusUnsafe(Duration.ofMillisUnsafe(1)))
    val sorted2 = AVector(tx1, tx0) ++ sorted0 ++ AVector(tx3, tx2)
    pool.flow.takeAllTxs(chainIndex.flattenIndex, Int.MaxValue) is sorted2

    val tx4 = genTx(gasPrice)
    pool.add(chainIndex, tx4, now)
    val sorted3 = AVector(tx1, tx0) ++ (sorted0 :+ tx3) ++
      (if (hashOrdering.lt(tx4.id.value, tx2.id.value)) AVector(tx2, tx4) else AVector(tx4, tx2))
    pool.flow.takeAllTxs(chainIndex.flattenIndex, Int.MaxValue) is sorted3

    (0 until sorted3.length).foreach { num =>
      pool.flow.takeAllTxs(chainIndex.flattenIndex, num) is sorted3.take(num)
    }
  }
}
