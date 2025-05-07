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

import akka.actor.Props
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import akka.util.Timeout

import org.alephium.flow.FlowFixture
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.InterCliqueManager
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._

abstract class ViewHandlerBaseSpec extends AlephiumActorSpec {
  trait Fixture extends FlowFixture with LockupScriptGenerators {
    lazy val minderAddresses =
      AVector.tabulate(groupConfig.groups)(i =>
        Address.Asset(addressGen(GroupIndex.unsafe(i)).sample.get._1)
      )
    lazy val viewHandler = TestActorRef[ViewHandler](
      Props(new ViewHandler(blockFlow, miningSetting.minerAddresses.map(_.map(_.lockupScript))))
    )

    def setSynced(): Unit = viewHandler ! InterCliqueManager.SyncedResult(true)
  }
}

class ViewHandlerSpec extends ViewHandlerBaseSpec {
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
      ViewHandler.needUpdatePreDanube(ChainIndex.unsafe(from, to)) is false
      ViewHandler.needUpdateDanube(ChainIndex.unsafe(from, to)) is (from equals to)
    }

    for {
      from <- Seq(1, 3)
      to   <- 0 until 4
    } {
      ViewHandler.needUpdatePreDanube(ChainIndex.unsafe(from, to)) is true
      ViewHandler.needUpdateDanube(ChainIndex.unsafe(from, to)) is true
    }
  }

  trait SyncedFixture extends Fixture {
    setSynced()
  }

  it should "not subscribe when miner addresses are not set" in new SyncedFixture {
    EventFilter.warning("Unable to subscribe the miner, as miner addresses are not set").intercept {
      viewHandler ! ViewHandler.Subscribe
      viewHandler.underlyingActor.subscribers.isEmpty is true
      expectMsg(ViewHandler.SubscribeResult(succeeded = false))
    }

    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler ! ViewHandler.Subscribe
    eventually {
      viewHandler.underlyingActor.subscribers.nonEmpty is true
      expectMsg(ViewHandler.SubscribeResult(succeeded = true))
    }

    viewHandler ! ViewHandler.Unsubscribe
    eventually {
      viewHandler.underlyingActor.subscribers.isEmpty is true
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
    blockFlow.getGrandPool().add(chainIndex, tx0, currentTs) is MemPool.AddedToMemPool(currentTs)
    blockFlow.getGrandPool().add(chainIndex, tx1, currentTs) is MemPool.AddedToMemPool(currentTs)
    mempool.contains(tx0) is true
    mempool.contains(tx1) is true
    mempool.isReady(tx0.id) is true
    mempool.isReady(tx1.id) is false
    mineFromMemPool(blockFlow, chainIndex).nonCoinbase.head.id is tx0.id
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
  }

  it should "schedule update pre-danube" in new Fixture {
    setHardForkBefore(HardFork.Danube)
    setSynced()
    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler ! ViewHandler.Subscribe
    eventually(viewHandler.underlyingActor.updateScheduledPreDanube.nonEmpty is true)
    eventually(expectMsgType[ViewHandler.NewTemplates])
    Thread.sleep(miningSetting.pollingInterval.millis)
    eventually(expectMsgType[ViewHandler.NewTemplates])
  }

  it should "not schedule update since-danube" in new Fixture {
    setHardForkSince(HardFork.Danube)
    setSynced()
    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler ! ViewHandler.Subscribe
    eventually(viewHandler.underlyingActor.updateScheduledPreDanube.isEmpty is true)
    eventually(expectMsgType[ViewHandler.NewTemplates])
    Thread.sleep(miningSetting.pollingInterval.millis)
    eventually(expectNoMessage())
  }

  trait DanubeUpdateSubscribersFixture extends Fixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.broker-num", 1),
      ("alephium.mining.polling-interval", "1 seconds")
    )
    setHardForkSince(HardFork.Danube)
    setSynced()
    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler ! ViewHandler.Subscribe
  }

  it should "schedule an update task for each chain index since-danube" in new DanubeUpdateSubscribersFixture {
    viewHandler.underlyingActor.updateScheduledDanube.forall(_.isEmpty) is true
    brokerConfig.chainIndexes.foreach { chainIndex =>
      val block = emptyBlock(blockFlow, chainIndex)
      viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    }
    eventually(viewHandler.underlyingActor.updateScheduledDanube.forall(_.nonEmpty) is true)
    eventually(expectMsgType[ViewHandler.NewTemplate])
    viewHandler ! ViewHandler.Unsubscribe
    eventually(viewHandler.underlyingActor.updateScheduledDanube.forall(_.isEmpty) is true)
  }

  it should "schedule an update task only if needed" in new Fixture {
    setHardForkSince(HardFork.Danube)
    setSynced()

    val tasks       = viewHandler.underlyingActor.updateScheduledDanube
    val chainIndex0 = ChainIndex.unsafe(0, 0)
    val chainIndex1 = ChainIndex.unsafe(1, 1)
    brokerConfig.contains(chainIndex0.from) is true
    brokerConfig.contains(chainIndex1.from) is false

    val block0 = emptyBlock(blockFlow, chainIndex0)
    val block1 = blockFlow.genesisBlocks(chainIndex1.from.value)(chainIndex1.to.value)
    viewHandler ! ChainHandler.FlowDataAdded(block0, DataOrigin.Local, TimeStamp.now())
    eventually(tasks.forall(_.isEmpty) is true)
    viewHandler ! ChainHandler.FlowDataAdded(block1.header, DataOrigin.Local, TimeStamp.now())
    eventually(tasks.forall(_.isEmpty) is true)

    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    eventually(viewHandler.underlyingActor.minerAddressesOpt.nonEmpty is true)
    viewHandler ! ChainHandler.FlowDataAdded(block0, DataOrigin.Local, TimeStamp.now())
    eventually(tasks.forall(_.isEmpty) is true)
    viewHandler ! ChainHandler.FlowDataAdded(block1.header, DataOrigin.Local, TimeStamp.now())
    eventually(tasks.forall(_.isEmpty) is true)

    viewHandler ! ViewHandler.Subscribe
    eventually(viewHandler.underlyingActor.subscribers.nonEmpty is true)
    viewHandler ! ChainHandler.FlowDataAdded(block0, DataOrigin.Local, TimeStamp.now())
    viewHandler ! ChainHandler.FlowDataAdded(block1.header, DataOrigin.Local, TimeStamp.now())
    eventually {
      tasks.head.nonEmpty is true
      tasks.tail.forall(_.isEmpty) is true
    }
  }

  it should "cancel the current update task and start a new one once the template update is complete" in new DanubeUpdateSubscribersFixture {
    val updateTasks = viewHandler.underlyingActor.updateScheduledDanube
    val chainIndex  = ChainIndex.unsafe(0, 0)
    val taskIndex   = chainIndex.flattenIndex
    updateTasks(taskIndex).isEmpty is true

    val block = emptyBlock(blockFlow, chainIndex)
    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually(updateTasks(taskIndex).nonEmpty is true)
    val task = updateTasks(taskIndex)

    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      updateTasks(taskIndex).nonEmpty is true
      updateTasks(taskIndex) isnot task
    }
    eventually(expectMsgType[ViewHandler.NewTemplate])
  }

  it should "schedule a new update task after it receives the UpdateSubscribersDanube message" in new DanubeUpdateSubscribersFixture {
    val updateTasks = viewHandler.underlyingActor.updateScheduledDanube
    val chainIndex  = ChainIndex.unsafe(0, 0)
    val taskIndex   = chainIndex.flattenIndex
    updateTasks(taskIndex).isEmpty is true

    viewHandler ! ViewHandler.UpdateSubscribersDanube(chainIndex)
    eventually(updateTasks(taskIndex).nonEmpty is true)
    val task0 = updateTasks(taskIndex)
    eventually(expectMsgType[ViewHandler.NewTemplate])
    eventually(expectMsgType[ViewHandler.NewTemplate])
    val task1 = updateTasks(taskIndex)
    task0 isnot task1
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

    viewHandler.underlyingActor.updateSubscribersPreDanube()
    eventually(probe0.expectNoMessage())
    eventually(probe1.expectNoMessage())

    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler.underlyingActor.updateSubscribersPreDanube()
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
    viewHandler ! ViewHandler.UpdateSubscribersPreDanube
    expectMsg(ViewHandler.SubscribeResult(false))
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
    blockFlow.getBestDepsPreDanube(block.chainIndex.from).deps.contains(block.hash) is false

    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      blockFlow.getBestFlowSkeleton().intraGroupTips.contains(block.hash) is true
      blockFlow
        .getBestDepsPreDanube(block.chainIndex.from)
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
    blockFlow.getBestDepsPreDanube(block.chainIndex.from).deps.contains(block.hash) is false

    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      blockFlow.getBestFlowSkeleton().intraGroupTips.contains(block.hash) is false
      blockFlow.getBestDepsPreDanube(block.chainIndex.from).deps.contains(block.hash) is true
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
    blockFlow.getBestDepsPreDanube(block.chainIndex.from).deps.contains(block.hash) is false

    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      blockFlow.getBestFlowSkeleton().intraGroupTips.contains(block.hash) is true
      blockFlow.getBestDepsPreDanube(block.chainIndex.from).deps.contains(block.hash) is true
    }
  }

  it should "update mempool properly since danube" in new DanubeFixture {
    setSynced()

    val chainIndex = ChainIndex.unsafe(0, 0)
    val tx         = transfer(blockFlow, chainIndex).nonCoinbase.head
    blockFlow.grandPool.add(chainIndex, tx.toTemplate, TimeStamp.now())
    val block = mineFromMemPool(blockFlow, chainIndex)
    addWithoutViewUpdate(blockFlow, block)
    blockFlow.getMemPool(chainIndex.from).contains(tx.id) is true
    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      blockFlow.getMemPool(chainIndex.from).contains(tx.id) is false
    }
  }
}

abstract class UpdateBestViewSpec extends ViewHandlerBaseSpec {
  behavior of "update best deps"

  trait UpdateBestViewFixture extends Fixture {
    def containBlockHashInBestDeps(blockHash: BlockHash): Boolean
    def setHardFork(): Unit
    def state: AsyncUpdateState
    def bestDepsUpdatedMsg: ViewHandler.Command
    def bestDepsUpdateFailedMsg: ViewHandler.Command

    setHardFork()
    setSynced()

    val chainIndex = ChainIndex.unsafe(0, 0)
    val block = {
      val block = emptyBlock(blockFlow, chainIndex)
      addWithoutViewUpdate(blockFlow, block)
      containBlockHashInBestDeps(block.hash) is false
      block
    }

    def setUpdating() = {
      state.requestUpdate()
      state.tryUpdate() is true
      state.requestUpdate()
      state.requestUpdate()
      state.requestCount is 2
      state.isUpdating is true
    }
  }

  def newFixture: UpdateBestViewFixture

  it should "update best deps after receiving FlowDataAdded event" in new AlephiumFixture {
    val fixture = newFixture
    import fixture._
    state.requestCount is 0
    state.isUpdating is false
    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())

    eventually {
      state.requestCount is 0
      state.isUpdating is false
      containBlockHashInBestDeps(block.hash) is true
    }
  }

  it should "not update best deps if the update task is running" in new AlephiumFixture {
    val fixture = newFixture
    import fixture._
    state.tryUpdate() is false
    state.requestUpdate()
    state.tryUpdate() is true
    state.requestCount is 0
    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    state.isUpdating is true
    state.tryUpdate() is false
    state.requestCount is 1
    containBlockHashInBestDeps(block.hash) is false

    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    state.isUpdating is true
    state.tryUpdate() is false
    state.requestCount is 2
    containBlockHashInBestDeps(block.hash) is false

    state.setCompleted()
    viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    eventually {
      state.requestCount is 0
      state.isUpdating is false
      containBlockHashInBestDeps(block.hash) is true
    }
  }

  it should "update best deps after receiving BestDepsUpdated event" in new AlephiumFixture {
    val fixture = newFixture
    import fixture._
    setUpdating()
    viewHandler ! bestDepsUpdatedMsg
    eventually {
      state.requestCount is 0
      state.isUpdating is false
      containBlockHashInBestDeps(block.hash) is true
    }
  }

  it should "not update best deps after receiving BestDepsUpdateFailed event" in new AlephiumFixture {
    val fixture = newFixture
    import fixture._
    setUpdating()
    viewHandler ! bestDepsUpdateFailedMsg
    eventually {
      state.requestCount is 2
      state.isUpdating is false
      containBlockHashInBestDeps(block.hash) is false
    }
  }
}

class PreDanubeUpdateBestViewSpec extends UpdateBestViewSpec {
  trait PreDanubeUpdateBestViewFixture extends UpdateBestViewFixture {
    def setHardFork(): Unit     = setHardForkBefore(HardFork.Danube)
    def state: AsyncUpdateState = viewHandler.underlyingActor.preDanubeUpdateState
    override def containBlockHashInBestDeps(blockHash: BlockHash): Boolean =
      blockFlow.getBestDepsPreDanube(chainIndex.from).deps.contains(blockHash)
    def bestDepsUpdatedMsg: ViewHandler.Command      = ViewHandler.BestDepsUpdatedPreDanube
    def bestDepsUpdateFailedMsg: ViewHandler.Command = ViewHandler.BestDepsUpdateFailedPreDanube
  }

  def newFixture: UpdateBestViewFixture = new PreDanubeUpdateBestViewFixture {}
}

class DanubeUpdateBestViewSpec extends UpdateBestViewSpec {
  trait DanubeUpdateBestViewFixture extends UpdateBestViewFixture {
    def setHardFork(): Unit = setHardForkSince(HardFork.Danube)
    def state: AsyncUpdateState =
      viewHandler.underlyingActor.danubeUpdateStates(chainIndex.flattenIndex)
    override def containBlockHashInBestDeps(blockHash: BlockHash): Boolean =
      blockFlow.getBestFlowSkeleton().intraGroupTips.contains(blockHash)
    def bestDepsUpdatedMsg: ViewHandler.Command = ViewHandler.BestDepsUpdatedDanube(chainIndex)
    def bestDepsUpdateFailedMsg: ViewHandler.Command =
      ViewHandler.BestDepsUpdateFailedDanube(chainIndex)
  }

  def newFixture: UpdateBestViewFixture = new DanubeUpdateBestViewFixture {}

  it should "update best view for all groups" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardFork(HardFork.Danube)
    setSynced()

    val interBlocks = brokerConfig.chainIndexes.filter(!_.isIntraGroup).map { chainIndex =>
      val block = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      blockFlow
        .getBestFlowSkeleton()
        .intraGroupTipOutTips
        .flatMap(identity)
        .contains(block.hash) is false
      block
    }

    val intraBlocks = brokerConfig.groupRange.map { group =>
      val chainIndex = ChainIndex.unsafe(group, group)
      val block      = emptyBlock(blockFlow, chainIndex)
      addWithoutViewUpdate(blockFlow, block)
      blockFlow.getBestFlowSkeleton().intraGroupTips.contains(block.hash) is false
      block
    }

    intraBlocks.foreach { block =>
      viewHandler ! ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
    }

    eventually {
      val bestFlowSkeleton = blockFlow.getBestFlowSkeleton()
      intraBlocks.foreach(block => bestFlowSkeleton.intraGroupTips.contains(block.hash) is true)

      val interTips = bestFlowSkeleton.intraGroupTipOutTips.flatMap(identity)
      interBlocks.foreach(block => interTips.contains(block.hash) is true)
    }
  }

  it should "test requestDanubeUpdate" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardFork(HardFork.Danube)

    brokerConfig.chainIndexes.foreach { chainIndex =>
      val state = viewHandler.underlyingActor.danubeUpdateStates(chainIndex.flattenIndex)
      state.requestCount is 0
      state.isUpdating is false

      viewHandler.underlyingActor.requestDanubeUpdate(chainIndex)
      state.requestCount is 1
      state.isUpdating is false

      viewHandler.underlyingActor.requestDanubeUpdate(chainIndex)
      state.requestCount is 2
      state.isUpdating is false
    }
  }

  it should "test setDanubeUpdateCompleted" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardFork(HardFork.Danube)

    brokerConfig.chainIndexes.foreach { chainIndex =>
      val state = viewHandler.underlyingActor.danubeUpdateStates(chainIndex.flattenIndex)
      state.requestUpdate()
      state.tryUpdate() is true
      state.isUpdating is true

      viewHandler.underlyingActor.setDanubeUpdateCompleted(chainIndex)
      state.isUpdating is false
    }
  }

  it should "test AsyncUpdateState" in {
    val state = AsyncUpdateState()
    state.isUpdating is false
    state.requestCount is 0

    state.requestUpdate()
    state.requestCount is 1
    state.tryUpdate() is true
    state.isUpdating is true
    state.requestCount is 0

    state.tryUpdate() is false
    state.setCompleted()
    state.isUpdating is false
    state.tryUpdate() is false

    state.requestUpdate()
    state.requestCount is 1
    state.isUpdating is false
    state.requestUpdate()
    state.requestCount is 2
    state.isUpdating is false
    state.tryUpdate() is true
    state.isUpdating is true
    state.requestCount is 0
  }
}
