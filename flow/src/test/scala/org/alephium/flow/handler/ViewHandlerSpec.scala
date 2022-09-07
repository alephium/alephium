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
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.InterCliqueManager
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Address, ChainIndex, GroupIndex, LockupScriptGenerators}
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

    val txProbe = TestProbe()
    lazy val minderAddresses =
      AVector.tabulate(groupConfig.groups)(i =>
        Address.Asset(addressGen(GroupIndex.unsafe(i)).sample.get._1)
      )
    lazy val viewHandler = TestActorRef[ViewHandler](ViewHandler.props(blockFlow, txProbe.ref))
  }

  trait SyncedFixture extends Fixture {
    viewHandler ! InterCliqueManager.SyncedResult(true)
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

    val tx1       = block1.nonCoinbase.head.toTemplate
    val currentTs = TimeStamp.now()
    blockFlow.getMemPool(chainIndex).pendingPool.add(tx1, currentTs)
    addWithoutViewUpdate(blockFlow, block0)

    viewHandler ! ViewHandler.UpdateMinerAddresses(minderAddresses)
    viewHandler ! ViewHandler.Subscribe
    viewHandler ! ChainHandler.FlowDataAdded(block0, DataOrigin.Local, TimeStamp.now())
    eventually(expectMsg(ViewHandler.SubscribeResult(succeeded = true)))
    eventually(txProbe.expectMsg(TxHandler.Broadcast(AVector(tx1 -> currentTs))))

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
    override val configValues = Map(("alephium.mining.polling-interval", "100 seconds"))
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
}
