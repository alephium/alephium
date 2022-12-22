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

import org.alephium.flow.FlowFixture
import org.alephium.protocol.model.{ChainIndex, GroupIndex, ModelGenerators}
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

class GrandPoolSpec extends AlephiumSpec {
  behavior of "Single Broker"

  trait SingleBrokerFixture extends Fixture with ModelGenerators {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    pool.mempools.foreach(_.size is 0)
  }

  it should "add intra-group transactions" in new SingleBrokerFixture {
    val randomGroup = brokerConfig.randomGroupIndex()
    val chainIndex  = ChainIndex(randomGroup, randomGroup)
    testIntraGroupTx(chainIndex)
  }

  it should "add cross-group transactions" in new SingleBrokerFixture {
    val chainIndex = chainIndexGen.retryUntil(!_.isIntraGroup).sample.get
    testXGroupTx(chainIndex)
  }

  behavior of "Multi Broker"

  trait MultiBrokerFixture extends Fixture with ModelGenerators {
    brokerConfig.brokerNum is 3
    brokerConfig.groups is 3

    pool.mempools.foreach(_.size is 0)
    pool.mempools.length is 1
  }

  it should "add intra-group transactions" in new MultiBrokerFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    testIntraGroupTx(chainIndex)
  }

  it should "add cross-group transactions" in new MultiBrokerFixture {
    val chainIndex = ChainIndex.unsafe(0, Random.between(1, brokerConfig.groups))
    testXGroupTx(chainIndex)
  }

  behavior of "Pool"

  it should "measure transactions" in new SingleBrokerFixture {
    def generateTx(from: Int, to: Int) = {
      val index = ChainIndex.unsafe(from, to)
      transactionGen().retryUntil(_.chainIndex equals index).sample.get.toTemplate
    }
    def checkMetrics(indexesWithTx: AVector[ChainIndex]) = {
      groupConfig.cliqueChainIndexes.map { chainIndex =>
        val expected = if (indexesWithTx.contains(chainIndex)) 1 else 0
        MemPool.sharedPoolTransactionsTotal
          .labels(chainIndex.from.value.toString, chainIndex.to.value.toString)
          .get() is expected.toDouble
      }
    }

    // Reset the metrics
    groupConfig.cliqueChainIndexes.foreach { chainIndex =>
      MemPool.sharedPoolTransactionsTotal
        .labels(chainIndex.from.value.toString, chainIndex.to.value.toString)
        .set(0)
    }

    val txs     = AVector(generateTx(0, 1), generateTx(1, 1), generateTx(1, 0))
    val indexes = txs.map(_.chainIndex)
    checkMetrics(AVector.empty)

    txs.foreach(tx => pool.add(tx.chainIndex, tx, TimeStamp.now()))
    checkMetrics(indexes)

    txs.foreach { tx =>
      val mempool = pool.getMemPool(tx.chainIndex.from)
      if (Random.nextBoolean()) {
        mempool.removeUsedTxs(AVector(tx))
      } else {
        mempool.removeUnusedTxs(AVector(tx))
      }
    }
    checkMetrics(AVector.empty)
  }

  trait Fixture extends FlowFixture {
    lazy val pool = GrandPool.empty

    def testIntraGroupTx(chainIndex: ChainIndex) = {
      chainIndex.isIntraGroup is true

      val block = transfer(blockFlow, chainIndex)
      val tx    = block.nonCoinbase.head.toTemplate
      pool.add(tx.chainIndex, tx, TimeStamp.now())
      brokerConfig.groupRange.foreach { group =>
        val groupIndex = GroupIndex.unsafe(group)
        val memPool    = pool.getMemPool(groupIndex)
        if (groupIndex == chainIndex.from) {
          memPool.size is 1
          tx.unsigned.inputs.foreach(input => memPool.isSpent(input.outputRef) is true)
          tx.assetOutputRefs.foreach(ref => memPool.getOutput(ref).nonEmpty is true)
        } else {
          memPool.size is 0
        }
      }
    }

    def testXGroupTx(chainIndex: ChainIndex) = {
      chainIndex.isIntraGroup is false

      val block = transfer(blockFlow, chainIndex)
      val tx    = block.nonCoinbase.head.toTemplate
      pool.add(tx.chainIndex, tx, TimeStamp.now())
      brokerConfig.groupRange.foreach { group =>
        val groupIndex = GroupIndex.unsafe(group)
        val memPool    = pool.getMemPool(groupIndex)
        if (groupIndex == chainIndex.from) {
          memPool.size is 1
          tx.unsigned.inputs.foreach(input =>
            memPool.sharedTxIndexes.inputIndex.contains(input.outputRef) is true
          )
          tx.unsigned.inputs.foreach(input => memPool.isSpent(input.outputRef) is true)
          tx.assetOutputRefs.foreach(ref =>
            if (ref.fromGroup == chainIndex.from) {
              memPool.getOutput(ref).nonEmpty is true
            } else {
              memPool.getOutput(ref).nonEmpty is false
            }
          )
        } else if (groupIndex == chainIndex.to) {
          memPool.size is 1
          tx.unsigned.inputs.foreach(input =>
            memPool.sharedTxIndexes.inputIndex.contains(input.outputRef) is false
          )
          tx.assetOutputRefs
            .foreach(ref =>
              if (ref.fromGroup == chainIndex.to) {
                memPool.getOutput(ref).nonEmpty is true
              } else {
                memPool.getOutput(ref).nonEmpty is false
              }
            )
        } else {
          memPool.size is 0
        }
      }
    }
  }
}
