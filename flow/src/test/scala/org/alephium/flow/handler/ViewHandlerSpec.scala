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

package org.alephium.flow.handler

import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import akka.util.Timeout

import org.alephium.flow.FlowFixture
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.InterCliqueManager
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{
  Address,
  ChainIndex,
  GroupIndex,
  HardFork,
  LockupScriptGenerators
}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._

class ViewHandlerSpec extends AlephiumActorSpec {
  it should "update when necessary" in {
    implicit val brokerConfig = new BrokerConfig {
      override def brokerId: Int  = 1
      override def brokerNum: Int = 2
      override def groups: Int    = 4
    }

    for {
      from <- Seq(0, 2)
      to   <- 0 until 4
    } {
      ViewHandler.needUpdate(ChainIndex.unsafe(from, to)) is (from equals to)
    }

    for {
      from <- Seq(1, 3)
      to   <- 0 until 4
    } {
      ViewHandler.needUpdate(ChainIndex.unsafe(from, to)) is true
    }
  }

  trait Fixture extends FlowFixture with LockupScriptGenerators {

    lazy val minderAddresses =
      AVector.tabulate(groupConfig.groups)(i =>
        Address.Asset(addressGen(GroupIndex.unsafe(i)).sample.get._1)
      )
    lazy val viewHandler = TestActorRef[ViewHandler](ViewHandler.props(blockFlow))

    def setSynced(): Unit = viewHandler ! InterCliqueManager.SyncedResult(true)
  }

  trait SyncedFixture extends Fixture {
    setSynced()
  }

  it should "not subscribe when miner addresses are not set" in new SyncedFixture {
    EventFilter.warning("Unable to subscribe the miner, as miner addresses are not set").intercept {
      viewHandler ! ViewHandler.Subscribe
      viewHandler.underlyingActor.subscribers.isEmpty is true
      viewHandler.underlyingActor.updateScheduled is None
      expectMsg(ViewHandler.SubscribeResult(succeeded = false))
    }

    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler ! ViewHandler.Subscribe
    eventually {
      viewHandler.underlyingActor.subscribers.nonEmpty is true
      viewHandler.underlyingActor.updateScheduled.nonEmpty is true
      expectMsg(ViewHandler.SubscribeResult(succeeded = true))
    }

    viewHandler ! ViewHandler.Unsubscribe
    eventually {
      viewHandler.underlyingActor.subscribers.isEmpty is true
      viewHandler.underlyingActor.updateScheduled is None
    }
  }

  it should "update deps and txs" in new SyncedFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val blockFlow1 = isolatedBlockFlow()
    val block0     = transfer(blockFlow1, chainIndex)
    addAndCheck(blockFlow1, block0)
    val block1 = transfer(blockFlow1, chainIndex)

    val tx0       = block0.nonCoinbase.head.toTemplate
    val tx1       = block1.nonCoinbase.head.toTemplate
    val currentTs = TimeStamp.now()
    val mempool   = blockFlow.getMemPool(chainIndex)
    blockFlow.getGrandPool().add(chainIndex, tx0, currentTs) is MemPool.AddedToMemPool
    blockFlow.getGrandPool().add(chainIndex, tx1, currentTs) is MemPool.AddedToMemPool
    mempool.contains(tx0) is true
    mempool.contains(tx1) is true
    mempool.isReady(tx0.id) is true
    mempool.isReady(tx1.id) is false
    addWithoutViewUpdate(blockFlow, block0)

    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler ! ViewHandler.Subscribe
    viewHandler ! ChainHandler.FlowDataAdded(block0, DataOrigin.Local, TimeStamp.now())
    eventually(expectMsg(ViewHandler.SubscribeResult(succeeded = true)))
    eventually {
      mempool.contains(tx0) is false
      mempool.contains(tx1) is true
      mempool.isReady(tx0.id) is false
      mempool.isReady(tx1.id) is true
    }

    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler ! ChainHandler.FlowDataAdded(block0, DataOrigin.Local, TimeStamp.now())
    eventually(expectMsgType[ViewHandler.NewTemplates])
  }

  it should "update templates automatically" in new SyncedFixture {
    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler ! ViewHandler.Subscribe

    Thread.sleep(miningSetting.pollingInterval.millis)
    eventually(expectMsgType[ViewHandler.NewTemplates])
  }

  it should "subscribe and unsubscribe actors" in new Fixture {
    override val configValues: Map[String, Any] =
      Map(("alephium.mining.polling-interval", "100 seconds"))
    viewHandler ! InterCliqueManager.SyncedResult(true)

    val probe0 = TestProbe()
    val probe1 = TestProbe()

    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    probe0.send(viewHandler, ViewHandler.Subscribe)
    eventually(viewHandler.underlyingActor.subscribers.toSeq is Seq(probe0.ref))
    probe0.send(viewHandler, ViewHandler.Subscribe)
    eventually(viewHandler.underlyingActor.subscribers.toSeq is Seq(probe0.ref))

    probe1.send(viewHandler, ViewHandler.Subscribe)
    eventually(viewHandler.underlyingActor.subscribers.toSeq is Seq(probe0.ref, probe1.ref))
    probe1.send(viewHandler, ViewHandler.Subscribe)
    eventually(viewHandler.underlyingActor.subscribers.toSeq is Seq(probe0.ref, probe1.ref))

    viewHandler.underlyingActor.updateSubscribers()
    eventually(probe0.expectNoMessage())
    eventually(probe1.expectNoMessage())

    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler.underlyingActor.updateSubscribers()
    eventually(probe0.expectMsgType[ViewHandler.NewTemplates])
    eventually(probe1.expectMsgType[ViewHandler.NewTemplates])

    probe0.send(viewHandler, ViewHandler.Unsubscribe)
    eventually(viewHandler.underlyingActor.subscribers.toSeq is Seq(probe1.ref))
    probe0.send(viewHandler, ViewHandler.Unsubscribe)
    eventually(viewHandler.underlyingActor.subscribers.toSeq is Seq(probe1.ref))

    probe1.send(viewHandler, ViewHandler.Unsubscribe)
    eventually(viewHandler.underlyingActor.subscribers.toSeq is Seq.empty)
  }

  it should "handle miner addresses" in new SyncedFixture with LockupScriptGenerators {
    implicit val askTimeout: Timeout = Timeout(Duration.ofSecondsUnsafe(1).asScala)

    viewHandler
      .ask(ViewHandler.GetMinerAddresses)
      .mapTo[Option[AVector[LockupScript]]]
      .futureValue is None

    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)

    viewHandler
      .ask(ViewHandler.GetMinerAddresses)
      .mapTo[Option[AVector[LockupScript.Asset]]]
      .futureValue is Some(minderAddresses.map(_.lockupScript))

    EventFilter.error(start = "Updating invalid miner addresses").intercept {
      viewHandler ! ViewHandler.UpdateMinerAddresses(AVector.empty)
    }
  }

  behavior of "unsynced node"

  trait UnsyncedFixture extends Fixture {
    viewHandler.underlyingActor.isNodeSynced is false
    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
  }

  it should "not accept subscribers" in new UnsyncedFixture {
    viewHandler ! ViewHandler.Subscribe
    expectMsg(ViewHandler.SubscribeResult(false))
  }

  it should "remove subscribes" in new UnsyncedFixture {
    viewHandler.underlyingActor.subscribers.addOne(testActor)
    viewHandler ! ViewHandler.UpdateSubscribers
    expectMsg(ViewHandler.SubscribeResult(false))
  }

  behavior of "update best deps"

  trait UpdateBestDepsFixture extends Fixture {
    setHardForkBefore(HardFork.Danube)

    setSynced()
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = emptyBlock(blockFlow, chainIndex)
    addWithoutViewUpdate(blockFlow, block)
    blockFlow.getBestDeps(chainIndex.from).deps.contains(block.hash) is false
  }

  it should "update best deps after receiving FlowDataAdded event" in new UpdateBestDepsFixture {
    viewHandler.underlyingActor.updatingBestViewCount is 0
    viewHandler.underlyingActor.updatingBestDeps is false
    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())

    eventually {
      viewHandler.underlyingActor.updatingBestViewCount is 0
      viewHandler.underlyingActor.updatingBestDeps is false
      blockFlow.getBestDeps(chainIndex.from).deps.contains(block.hash) is true
    }
  }

  it should "not update best deps if the update task is running" in new UpdateBestDepsFixture {
    viewHandler.underlyingActor.updatingBestDeps = true
    viewHandler.underlyingActor.updatingBestViewCount is 0
    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    viewHandler.underlyingActor.updatingBestDeps is true
    viewHandler.underlyingActor.updatingBestViewCount is 1
    blockFlow.getBestDeps(chainIndex.from).deps.contains(block.hash) is false

    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    viewHandler.underlyingActor.updatingBestDeps is true
    viewHandler.underlyingActor.updatingBestViewCount is 2
    blockFlow.getBestDeps(chainIndex.from).deps.contains(block.hash) is false

    viewHandler.underlyingActor.updatingBestDeps = false
    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      viewHandler.underlyingActor.updatingBestViewCount is 0
      viewHandler.underlyingActor.updatingBestDeps is false
      blockFlow.getBestDeps(chainIndex.from).deps.contains(block.hash) is true
    }
  }

  it should "update best deps after receiving BestDepsUpdated event" in new UpdateBestDepsFixture {
    viewHandler.underlyingActor.updatingBestViewCount = 2
    viewHandler.underlyingActor.updatingBestDeps = true
    viewHandler ! ViewHandler.BestDepsUpdatedPreDanube
    eventually {
      viewHandler.underlyingActor.updatingBestViewCount is 0
      viewHandler.underlyingActor.updatingBestDeps is false
      blockFlow.getBestDeps(chainIndex.from).deps.contains(block.hash) is true
    }
  }

  it should "not update best deps after receiving BestDepsUpdateFailed event" in new UpdateBestDepsFixture {
    viewHandler.underlyingActor.updatingBestViewCount = 2
    viewHandler.underlyingActor.updatingBestDeps = true
    viewHandler ! ViewHandler.BestDepsUpdateFailedPreDanube
    eventually {
      viewHandler.underlyingActor.updatingBestViewCount is 2
      viewHandler.underlyingActor.updatingBestDeps is false
      blockFlow.getBestDeps(chainIndex.from).deps.contains(block.hash) is false
    }
  }

  trait DanubeFixture extends Fixture {
    setHardForkSince(HardFork.Danube)

    def createSubscriber(): TestProbe = {
      val probe = TestProbe()
      viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
      probe.send(viewHandler, ViewHandler.Subscribe)
      eventually(viewHandler.underlyingActor.subscribers.toSeq is Seq(probe.ref))
      probe
    }
  }

  it should "notify subscribers once the block is added since danube" in new DanubeFixture {
    setSynced()
    val probe = createSubscriber()
    val block = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually(probe.expectMsgType[ViewHandler.NewTemplate])
  }

  it should "not notify subscribers when the best view is updated since danube" in new DanubeFixture {
    setSynced()
    val probe = createSubscriber()
    viewHandler ! ViewHandler.BestDepsUpdatedPreDanube
    eventually(probe.expectNoMessage())
  }

  it should "only update the best flow skeleton if the danube upgrade is activated" in new DanubeFixture {
    setSynced()
    val block = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    addWithoutViewUpdate(blockFlow, block)

    blockFlow.getBestFlowSkeleton().intraGroupTips.contains(block.hash) is false
    blockFlow.getBestDeps(block.chainIndex.from, HardFork.Rhone).deps.contains(block.hash) is false

    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      blockFlow.getBestFlowSkeleton().intraGroupTips.contains(block.hash) is true
      blockFlow
        .getBestDeps(block.chainIndex.from, HardFork.Rhone)
        .deps
        .contains(block.hash) is false
    }
  }

  it should "only update the best deps if the danube upgrade will not be activated soon" in new Fixture {
    val now = TimeStamp.now()
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.rhone-hard-fork-timestamp", now.millis),
      ("alephium.network.danube-hard-fork-timestamp", now.plusSecondsUnsafe(20).millis)
    )
    setSynced()

    val block = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    addWithoutViewUpdate(blockFlow, block)

    blockFlow.getBestFlowSkeleton().intraGroupTips.contains(block.hash) is false
    blockFlow.getBestDeps(block.chainIndex.from).deps.contains(block.hash) is false

    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      blockFlow.getBestFlowSkeleton().intraGroupTips.contains(block.hash) is false
      blockFlow.getBestDeps(block.chainIndex.from).deps.contains(block.hash) is true
    }
  }

  it should "update the best view when the danube upgrade is about to be activated" in new Fixture {
    val now = TimeStamp.now()
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.rhone-hard-fork-timestamp", now.millis),
      ("alephium.network.danube-hard-fork-timestamp", now.plusSecondsUnsafe(5).millis)
    )
    setSynced()

    val block = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    addWithoutViewUpdate(blockFlow, block)

    blockFlow.getBestFlowSkeleton().intraGroupTips.contains(block.hash) is false
    blockFlow.getBestDeps(block.chainIndex.from).deps.contains(block.hash) is false

    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      blockFlow.getBestFlowSkeleton().intraGroupTips.contains(block.hash) is true
      blockFlow.getBestDeps(block.chainIndex.from).deps.contains(block.hash) is true
    }
  }

  it should "update mempool properly since danube" in new DanubeFixture {
    setSynced()

    val chainIndex = ChainIndex.unsafe(0, 0)
    val tx         = transfer(blockFlow, chainIndex).nonCoinbase.head
    blockFlow.grandPool.add(chainIndex, tx.toTemplate, TimeStamp.now())
    val block = mineFromMemPool(blockFlow, chainIndex)
    addAndCheck0(blockFlow, block)
    blockFlow.getMemPool(chainIndex.from).contains(tx.id) is true
    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      blockFlow.getMemPool(chainIndex.from).contains(tx.id) is false
    }
  }
}
