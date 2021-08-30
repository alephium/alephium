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
import org.scalatest.concurrent.Eventually.eventually

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector}

class TxHandlerSpec extends AlephiumFlowActorSpec {

  it should "broadcast valid tx" in new Fixture {
    override val configValues = Map(
      ("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"),
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 1),
      ("alephium.broker.broker-id", 0)
    )

    def createTx(chainIndex: ChainIndex): Transaction = {
      transferTxs(blockFlow, chainIndex, ALF.alf(1), 1, None, true, None).head
    }

    val txs =
      AVector.tabulate(groupConfig.groups)(groupId => createTx(ChainIndex.unsafe(groupId, groupId)))
    txs.length is 4
    txHandler.underlyingActor.txsBuffer.isEmpty is true

    val txs0 = txs.take(2)
    txs0.foreach(txHandler ! addTx(_))
    txs0.foreach(tx => expectMsg(TxHandler.AddSucceeded(tx.id)))
    broadcastTxProbe.expectMsgPF() { case InterCliqueManager.BroadCastTx(hashes) =>
      hashes.length is 2
      hashes.contains((ChainIndex.unsafe(0, 0), AVector(txs0.head.id))) is true
      hashes.contains((ChainIndex.unsafe(1, 1), AVector(txs0.last.id))) is true
    }
    // use eventually here to avoid test failure on windows
    eventually(txHandler.underlyingActor.txsBuffer.isEmpty is true)

    val txs1 = txs.drop(2)
    txs1.foreach(txHandler ! addTx(_))
    txs1.foreach(tx => expectMsg(TxHandler.AddSucceeded(tx.id)))
    broadcastTxProbe.expectMsgPF() { case InterCliqueManager.BroadCastTx(hashes) =>
      hashes.length is 2
      hashes.contains((ChainIndex.unsafe(2, 2), AVector(txs1.head.id))) is true
      hashes.contains((ChainIndex.unsafe(3, 3), AVector(txs1.last.id))) is true
    }
    // use eventually here to avoid test failure on windows
    eventually(txHandler.underlyingActor.txsBuffer.isEmpty is true)

    // won't broadcast when there are no txs in buffer
    broadcastTxProbe.expectNoMessage()
  }

  it should "not broadcast invalid tx" in new Fixture {
    val tx = transactionGen(chainIndexGen = Gen.const(chainIndex)).sample.get
    txHandler ! addTx(tx)

    expectMsg(TxHandler.AddFailed(tx.id))
    broadcastTxProbe.expectNoMessage()
  }

  it should "fail in case of duplicate txs" in new Fixture {
    override val configValues = Map(("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"))

    val tx = transferTxs(blockFlow, chainIndex, ALF.alf(1), 1, None, true, None).head

    txHandler ! addTx(tx)
    expectMsg(TxHandler.AddSucceeded(tx.id))
    broadcastTxProbe.expectMsg(
      InterCliqueManager.BroadCastTx(AVector((chainIndex, AVector(tx.id))))
    )

    EventFilter.warning(pattern = ".*already existed.*").intercept {
      txHandler ! addTx(tx)
      expectMsg(TxHandler.AddFailed(tx.id))
      broadcastTxProbe.expectNoMessage()
    }
  }

  it should "fail in double-spending" in new Fixture {
    override val configValues = Map(("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"))

    val tx0 = transferTxs(blockFlow, chainIndex, ALF.alf(1), 1, None, true, None).head
    val tx1 = transferTxs(blockFlow, chainIndex, ALF.alf(2), 1, None, true, None).head

    txHandler ! addTx(tx0)
    expectMsg(TxHandler.AddSucceeded(tx0.id))
    broadcastTxProbe.expectMsg(
      InterCliqueManager.BroadCastTx(AVector((chainIndex, AVector(tx0.id))))
    )

    EventFilter.warning(pattern = ".*double spending.*").intercept {
      txHandler ! addTx(tx1)
      expectMsg(TxHandler.AddFailed(tx1.id))
      broadcastTxProbe.expectNoMessage()
    }
  }

  it should "download txs" in new Fixture {
    override val configValues = Map(
      ("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"),
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 1),
      ("alephium.broker.broker-id", 0)
    )

    def sendAnnouncement(
        chainIndex: ChainIndex,
        hash: Hash,
        haveAnnouncement: Boolean
    ): (TestProbe, AVector[(ChainIndex, AVector[Hash])]) = {
      val brokerHandler = TestProbe()
      val announcement  = TxHandler.Announcement(ActorRefT(brokerHandler.ref), chainIndex, hash)
      brokerHandler.send(txHandler, TxHandler.TxAnnouncements(AVector((chainIndex, AVector(hash)))))
      txHandler.underlyingActor.fetching.states.contains(hash) is true
      txHandler.underlyingActor.announcements.contains(announcement) is haveAnnouncement
      brokerHandler -> AVector(chainIndex -> AVector(hash))
    }

    val chain01     = ChainIndex.unsafe(0, 1)
    val txHash1     = Hash.generate
    val maxCapacity = (brokerConfig.groupNumPerBroker * brokerConfig.groups * 10) * 32

    txHandler.underlyingActor.maxCapacity is maxCapacity
    txHandler.underlyingActor.announcements.isEmpty is true

    (0 until TxHandler.MaxDownloadTimes)
      .map(_ => sendAnnouncement(chain01, txHash1, true))
      .foreach { case (brokerHandler, hashes) =>
        brokerHandler.expectMsg(BrokerHandler.DownloadTxs(hashes))
      }
    eventually(txHandler.underlyingActor.announcements.isEmpty is true)

    val (brokerHandler, _) = sendAnnouncement(chain01, txHash1, false)
    brokerHandler.expectNoMessage()
    txHandler.underlyingActor.announcements.isEmpty is true

    val chain02 = ChainIndex.unsafe(0, 2)
    val chain03 = ChainIndex.unsafe(0, 3)
    val tx2     = transactionGen(chainIndexGen = Gen.const(chain02)).sample.get.toTemplate
    val txHash3 = Hash.generate
    val txHash4 = Hash.generate
    val mempool = blockFlow.getMemPool(chain02)
    mempool.contains(chain02, tx2.id) is false
    mempool.addNewTx(chain02, tx2)
    mempool.contains(chain02, tx2.id) is true

    txHandler ! TxHandler.TxAnnouncements(
      AVector(
        (chain01, AVector(txHash1, txHash3)),
        (chain02, AVector(tx2.id))
      )
    )
    txHandler ! TxHandler.TxAnnouncements(
      AVector(
        (chain03, AVector(txHash4))
      )
    )
    expectMsg(
      BrokerHandler.DownloadTxs(
        AVector(
          (chain01, AVector(txHash3)),
          (chain03, AVector(txHash4))
        )
      )
    )
    eventually(txHandler.underlyingActor.announcements.isEmpty is true)
  }

  trait PeriodicTaskFixture extends FlowFixture {
    implicit lazy val system: ActorSystem = createSystem(Some(AlephiumActorSpec.debugConfig))

    def test(message: String) = {
      EventFilter.debug(message, occurrences = 5).intercept {
        system.actorOf(TxHandler.props(blockFlow))
      }
    }
  }

  it should "broadcast txs regularly" in new PeriodicTaskFixture {
    override val configValues = Map(("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"))

    test("Start to broadcast txs")
  }

  it should "download txs regularly" in new PeriodicTaskFixture {
    override val configValues = Map(("alephium.mempool.batch-download-txs-frequency", "200 ms"))

    test("Start to download txs")
  }

  it should "clean tx pool regularly" in new PeriodicTaskFixture {
    override val configValues = Map(("alephium.mempool.clean-frequency", "300 ms"))

    test("Start to clean tx pools")
  }

  trait Fixture extends FlowFixture with TxGenerators {
    // use lazy here because we want to override config values
    lazy val chainIndex = ChainIndex.unsafe(0, 0)
    lazy val txHandler  = TestActorRef[TxHandler](TxHandler.props(blockFlow))

    def addTx(tx: Transaction) = TxHandler.AddToSharedPool(AVector(tx.toTemplate))

    val broadcastTxProbe = TestProbe()
    system.eventStream.subscribe(broadcastTxProbe.ref, classOf[InterCliqueManager.BroadCastTx])
  }
}
