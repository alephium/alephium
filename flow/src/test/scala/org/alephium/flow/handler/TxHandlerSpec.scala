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

import akka.actor.ActorSystem
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import org.scalacheck.Gen

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.protocol.ALF
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumActorSpec, AVector}

class TxHandlerSpec extends AlephiumFlowActorSpec {

  it should "broadcast valid tx" in new Fixture {
    val tx = transferTxs(blockFlow, chainIndex, ALF.alf(1), 1, None, true, None).head

    txHandler ! addTx(tx)

    expectMsg(TxHandler.AddSucceeded(tx.id))

    broadcastTxProbe.expectMsg(
      CliqueManager.BroadCastTx(AVector(tx.id), chainIndex, dataOrigin)
    )
  }

  it should "not broadcast invalid tx" in new Fixture {
    val tx = transactionGen(chainIndexGen = Gen.const(chainIndex)).sample.get
    txHandler ! addTx(tx)

    expectMsg(TxHandler.AddFailed(tx.id))
    broadcastTxProbe.expectNoMessage()
  }

  it should "fail in case of duplicate txs" in new Fixture {
    val tx = transferTxs(blockFlow, chainIndex, ALF.alf(1), 1, None, true, None).head

    txHandler ! addTx(tx)
    expectMsg(TxHandler.AddSucceeded(tx.id))

    EventFilter.warning(pattern = ".*already existed.*").intercept {
      txHandler ! addTx(tx)
      expectMsg(TxHandler.AddFailed(tx.id))
    }
  }

  it should "fail in double-spending" in new Fixture {
    val tx0 = transferTxs(blockFlow, chainIndex, ALF.alf(1), 1, None, true, None).head
    val tx1 = transferTxs(blockFlow, chainIndex, ALF.alf(2), 1, None, true, None).head

    txHandler ! addTx(tx0)
    expectMsg(TxHandler.AddSucceeded(tx0.id))

    EventFilter.warning(pattern = ".*double spending.*").intercept {
      txHandler ! addTx(tx1)
      expectMsg(TxHandler.AddFailed(tx1.id))
    }
  }

  it should "fetch txs" in new Fixture {
    val chainIndexGen = Gen.const(chainIndex)
    val tx1           = transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate
    val memPool       = blockFlow.getMemPool(chainIndex)
    val maxCapacity   = (brokerConfig.groupNumPerBroker * brokerConfig.groups * 10) * 32

    txHandler.underlyingActor.maxCapacity is maxCapacity
    (0 until TxHandler.MaxDownloadTimes).foreach { _ =>
      txHandler ! TxHandler.TxAnnouncements(AVector((chainIndex, AVector(tx1.id))))
      expectMsg(BrokerHandler.DownloadTxs(AVector((chainIndex, AVector(tx1.id)))))
      txHandler.underlyingActor.fetching.states.contains(tx1.id) is true
    }
    txHandler ! TxHandler.TxAnnouncements(AVector((chainIndex, AVector(tx1.id))))
    expectNoMessage()

    val tx2 = transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate
    val tx3 = transactionGen(chainIndexGen = chainIndexGen).sample.get.toTemplate
    memPool.contains(chainIndex, tx2.id) is false
    memPool.addNewTx(chainIndex, tx2)
    memPool.contains(chainIndex, tx2.id) is true
    txHandler ! TxHandler.TxAnnouncements(
      AVector((chainIndex, AVector(tx1.id, tx2.id, tx3.id)))
    )
    expectMsg(BrokerHandler.DownloadTxs(AVector((chainIndex, AVector(tx3.id)))))
  }

  it should "clean tx pool regularly" in new FlowFixture {
    override val configValues = Map(("alephium.mempool.clean-frequency", "300 ms"))

    implicit lazy val system: ActorSystem = createSystem(Some(AlephiumActorSpec.debugConfig))

    EventFilter.debug("Start to clean tx pools", occurrences = 5).intercept {
      system.actorOf(TxHandler.props(blockFlow))
    }
  }

  trait Fixture extends FlowFixture with TxGenerators {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val dataOrigin = DataOrigin.Local

    def addTx(tx: Transaction) = TxHandler.AddToSharedPool(AVector(tx.toTemplate), dataOrigin)

    val txHandler = TestActorRef[TxHandler](TxHandler.props(blockFlow))

    val broadcastTxProbe = TestProbe()
    system.eventStream.subscribe(broadcastTxProbe.ref, classOf[CliqueManager.BroadCastTx])
  }
}
