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

package org.alephium.flow.client

import akka.testkit.TestProbe
import org.scalacheck.Gen

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.handler.{AllHandlers, BlockChainHandler, TestUtils}
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.setting.MiningSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{ActorRefT, AVector, UnsecureRandom, TimeStamp}

class MinerStateSpec extends AlephiumFlowActorSpec("FairMinerState") { Spec =>
  trait Fixture extends MinerState {
    implicit override def brokerConfig: BrokerConfig  = config.broker
    implicit override def miningConfig: MiningSetting = config.mining

    val handlers: AllHandlers = TestUtils.createBlockHandlersProbe._1
    val probes                = AVector.fill(brokerConfig.groupNumPerBroker, brokerConfig.groups)(TestProbe())

    override def prepareTemplate(fromShift: Int, to: Int): BlockTemplate = {
      val index        = ChainIndex.unsafe(brokerConfig.groupFrom + fromShift, to)
      val flowTemplate = blockFlow.prepareBlockFlowUnsafe(index)
      BlockTemplate(flowTemplate.deps, flowTemplate.target, TimeStamp.now(), AVector.empty)
    }

    override def startTask(
        fromShift: Int,
        to: Int,
        template: BlockTemplate,
        blockHandler: ActorRefT[BlockChainHandler.Command]
    ): Unit = {
      probes(fromShift)(to).ref ! template
    }
  }

  it should "use correct collections" in new Fixture {
    miningCounts.length is brokerConfig.groupNumPerBroker
    miningCounts.foreach(_.length is brokerConfig.groups)
    running.length is brokerConfig.groupNumPerBroker
    running.foreach(_.length is brokerConfig.groups)
    pendingTasks.length is brokerConfig.groupNumPerBroker
    pendingTasks.foreach(_.length is brokerConfig.groups)
  }

  it should "start new tasks correctly" in new Fixture {
    startNewTasks()
    probes.foreach(_.foreach(_.expectMsgType[BlockTemplate]))
    running.foreach(_.foreach(_ is true))
  }

  it should "be idle after stopping" in new Fixture {
    postMinerStop()
    running.foreach(_.foreach(_ is false))
  }

  it should "handle mining counts correctly" in new Fixture {
    forAll(
      Gen.choose(0, brokerConfig.groupNumPerBroker - 1),
      Gen.choose(0, brokerConfig.groups - 1)
    ) { (fromShift, to) =>
      val oldCount   = getMiningCount(fromShift, to)
      val countDelta = UnsecureRandom.source.nextInt(Integer.MAX_VALUE)
      increaseCounts(fromShift, to, countDelta)
      val newCount = getMiningCount(fromShift, to)
      (newCount - oldCount) is countDelta
    }
  }

  it should "pick up correct task" in new Fixture {
    val fromShift = UnsecureRandom.source.nextInt(brokerConfig.groupNumPerBroker)
    val to        = UnsecureRandom.source.nextInt(brokerConfig.groups)
    (0 until brokerConfig.groups).foreach { i =>
      if (i != to) increaseCounts(fromShift, i, miningConfig.nonceStep + 1)
    }
    startNewTasks()
    (0 until brokerConfig.groups).foreach { i =>
      if (i != to) {
        probes(fromShift)(i).expectNoMessage()
      } else {
        probes(fromShift)(i).expectMsgType[BlockTemplate]
      }
    }
  }
}
