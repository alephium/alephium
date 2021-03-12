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

import akka.testkit.TestProbe
import org.scalacheck.Gen

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.FlowFixture
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.protocol.ALF
import org.alephium.protocol.message.{Message, SendTxs}
import org.alephium.protocol.model._
import org.alephium.util.AVector

class TxHandlerSpec extends AlephiumFlowActorSpec("TxHandlerSpec") {

  it should "broadcast valid tx" in new Fixture {
    val tx = transferTxs(blockFlow, chainIndex, ALF.alf(1), 1, None, true, None).head

    txHandler ! addTx(tx)

    expectMsg(TxHandler.AddSucceeded(tx.id))

    broadcastTxProbe.expectMsg(
      CliqueManager.BroadCastTx(tx.toTemplate, txMessage(tx), chainIndex, dataOrigin))
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

    txHandler ! addTx(tx)
    expectMsg(TxHandler.AddFailed(tx.id))
  }

  trait Fixture extends FlowFixture with TxGenerators {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val dataOrigin = DataOrigin.Local

    def txMessage(tx: Transaction) =
      Message.serialize(SendTxs(AVector(tx.toTemplate)), networkSetting.networkType)
    def addTx(tx: Transaction) = TxHandler.AddTx(tx.toTemplate, dataOrigin)

    val txHandler = system.actorOf(TxHandler.props(blockFlow)(brokerConfig, networkSetting))

    val broadcastTxProbe = TestProbe()
    system.eventStream.subscribe(broadcastTxProbe.ref, classOf[CliqueManager.BroadCastTx])
  }
}
