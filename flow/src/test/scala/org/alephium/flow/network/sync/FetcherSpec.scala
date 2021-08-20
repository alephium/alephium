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

package org.alephium.flow.network.sync

import akka.actor.Props
import akka.testkit.TestActorRef
import org.scalacheck.Gen

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{ChainIndex, TxGenerators}
import org.alephium.util.{AlephiumActorSpec, AVector, UnsecureRandom}

class FetcherSpec extends AlephiumActorSpec("fetcher") {
  class TestFetcher(val blockflow: BlockFlow)(implicit
      val brokerConfig: BrokerConfig,
      val networkSetting: NetworkSetting
  ) extends Fetcher {

    var isNodeSynced: Boolean = false

    override def receive: Receive = {
      case BlockFlowSynchronizer.TxsAnnouncement(hashes) =>
        handleTxsAnnouncement(hashes)
      case BlockFlowSynchronizer.BlockAnnouncement(hash) =>
        handleBlockAnnouncement(hash)
    }
  }

  object TestFetcher {
    def props(blockflow: BlockFlow)(implicit
        brokerConfig: BrokerConfig,
        networkSetting: NetworkSetting
    ): Props = Props(new TestFetcher(blockflow))
  }

  trait Fixture extends AlephiumFlowSpec {
    lazy val fetcher = TestActorRef[TestFetcher](TestFetcher.props(blockFlow))
  }

  it should "fetch block" in new Fixture {
    val blockHash = BlockHash.generate
    (0 until Fetcher.MaxDownloadTimes).foreach { _ =>
      fetcher ! BlockFlowSynchronizer.BlockAnnouncement(blockHash)
      expectMsg(BrokerHandler.DownloadBlocks(AVector(blockHash)))
      fetcher.underlyingActor.fetchingBlocks.contains(blockHash) is true
    }
    fetcher ! BlockFlowSynchronizer.BlockAnnouncement(blockHash)
    expectNoMessage()

    val brokerGroup = UnsecureRandom.sample(brokerConfig.groupRange)
    val chainIndex  = ChainIndex.unsafe(brokerGroup, brokerGroup)
    val block       = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)
    blockFlow.containsUnsafe(block.hash) is true
    fetcher ! BlockFlowSynchronizer.BlockAnnouncement(block.hash)
    expectNoMessage()
    fetcher.underlyingActor.fetchingBlocks.contains(block.hash) is false
  }

  it should "fetch txs" in new Fixture with TxGenerators {
    val brokerGroup   = UnsecureRandom.sample(brokerConfig.groupRange)
    val chainIndex    = ChainIndex.unsafe(brokerGroup, brokerGroup)
    val chainIndexGen = Gen.const(chainIndex)
    val tx1           = transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate
    val memPool       = blockFlow.getMemPool(chainIndex)

    fetcher ! BlockFlowSynchronizer.TxsAnnouncement(AVector((chainIndex, AVector(tx1.id))))
    expectNoMessage()
    fetcher.underlyingActor.fetchingTxs.contains(tx1.id) is false
    fetcher.underlyingActor.isNodeSynced = true
    (0 until Fetcher.MaxDownloadTimes).foreach { _ =>
      fetcher ! BlockFlowSynchronizer.TxsAnnouncement(AVector((chainIndex, AVector(tx1.id))))
      expectMsg(BrokerHandler.DownloadTxs(AVector((chainIndex, AVector(tx1.id)))))
      fetcher.underlyingActor.fetchingTxs.contains(tx1.id) is true
    }
    fetcher ! BlockFlowSynchronizer.TxsAnnouncement(AVector((chainIndex, AVector(tx1.id))))
    expectNoMessage()

    val tx2 = transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate
    val tx3 = transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate
    memPool.contains(chainIndex, tx2.id) is false
    memPool.addNewTx(chainIndex, tx2)
    memPool.contains(chainIndex, tx2.id) is true
    fetcher ! BlockFlowSynchronizer.TxsAnnouncement(
      AVector((chainIndex, AVector(tx1.id, tx2.id, tx3.id)))
    )
    expectMsg(BrokerHandler.DownloadTxs(AVector((chainIndex, AVector(tx3.id)))))
  }

  it should "cleanup cache based on capacity and timestamp" in new Fixture {
    override val configValues = Map(
      ("alephium.broker.broker-num", 2),
      ("alephium.broker.groups", 2),
      ("alephium.network.sync-expiry-period", "2s")
    )

    val fetcherObj = fetcher.underlyingActor
    fetcherObj.maxBlockCapacity is (2 * 1 * 10)
    fetcherObj.maxTxsCapacity is (2 * 1 * 10) * 32
    val hash0 = BlockHash.generate
    fetcher ! BlockFlowSynchronizer.BlockAnnouncement(hash0)
    expectMsg(BrokerHandler.DownloadBlocks(AVector(hash0)))
    fetcherObj.fetchingBlocks.contains(hash0) is true
    val hashes = (0 until fetcherObj.maxBlockCapacity).map { _ =>
      val hash = BlockHash.generate
      fetcher ! BlockFlowSynchronizer.BlockAnnouncement(hash)
      expectMsg(BrokerHandler.DownloadBlocks(AVector(hash)))
      fetcherObj.fetchingBlocks.contains(hash) is true
      hash
    }
    fetcherObj.fetchingBlocks.contains(hash0) is false
    fetcherObj.fetchingBlocks.keys().toSet is hashes.toSet
    Thread.sleep(2500)
    val hash1 = BlockHash.generate
    fetcher ! BlockFlowSynchronizer.BlockAnnouncement(hash1)
    expectMsg(BrokerHandler.DownloadBlocks(AVector(hash1)))
    fetcherObj.fetchingBlocks.contains(hash1) is true
    fetcherObj.fetchingBlocks.keys().toSet is Set(hash1)
  }
}
